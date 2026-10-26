// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MissingPermission")

package io.github.bytenomad47.a20.device

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import io.github.bytenomad47.a20.protocol.*
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns only a secure socket to the A6611 vendor control UUID. */
class RfcommTransport(
    private val adapter: BluetoothAdapter,
    private val address: () -> String?,
    private val allowModeWrites: Boolean = false,
) : DeviceGateway {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private val requestMutex = Mutex()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var generation = 0L
    private var reader: Job? = null
    @Volatile private var lastSnapshot: DeviceSnapshot? = null
    @Volatile
    var lastStateBodyBytes: Int? = null
        private set

    private val packets = MutableSharedFlow<Result<Packet>>(extraBufferCapacity = 16)
    private val mutableEvents = MutableSharedFlow<DeviceSnapshot>(extraBufferCapacity = 8)
    val events = mutableEvents.asSharedFlow()
    private val mutablePlaybackEvents =
        MutableSharedFlow<SleepPlaybackStatus>(extraBufferCapacity = 8)
    override val playbackEvents = mutablePlaybackEvents.asSharedFlow()
    val connected: Boolean
        get() = socket?.isConnected == true

    var onDisconnect: (() -> Unit)? = null
    var onFindStopped: (() -> Unit)? = null

    private suspend fun connect() = connectMutex.withLock {
        if (socket?.isConnected == true) return@withLock
        if (!adapter.isEnabled) throw IOException("Bluetooth disabled")
        val selected = address() ?: throw IOException("Select an A20 first")
        val target = adapter.getRemoteDevice(selected)
        val candidate =
            target.createRfcommSocketToServiceRecord(UUID.fromString(A20Protocol.SERVICE_UUID))
        val currentGeneration = ++generation
        socket = candidate // close() also interrupts a blocked connect/read.
        try {
            socketOperation(candidate) { candidate.connect() }
            reader = scope.launch {
                val decoder = FrameDecoder()
                val bytes = ByteArray(1024)
                try {
                    val input = candidate.inputStream
                    while (isActive) {
                        val n = input.read(bytes)
                        if (n < 0) {
                            decoder.endOfStream()
                            throw IOException("Control connection closed")
                        }
                        if (n == 0) continue
                        for (packet in decoder.feed(bytes.copyOf(n))) {
                            if (
                                packet.command == A20Protocol.STATE_COMMAND &&
                                    packet.body.isNotEmpty()
                            ) {
                                lastStateBodyBytes = packet.body.size
                                val state = A20StateParser.parse(packet)
                                lastSnapshot = state
                                mutableEvents.emit(state)
                            }
                            SleepPlaybackProtocol.notification(packet)?.let { status ->
                                lastSnapshot?.let { previous ->
                                    SleepPlaybackProtocol.apply(previous, status)?.let {
                                        lastSnapshot = it
                                    }
                                }
                                mutablePlaybackEvents.emit(status)
                            }
                            if (FindProtocol.isStopNotification(packet)) onFindStopped?.invoke()
                            packets.emit(Result.success(packet))
                        }
                    }
                } catch (_: CancellationException) {} catch (e: Exception) {
                    if (generation == currentGeneration) {
                        packets.emit(Result.failure(e))
                        close()
                        onDisconnect?.invoke()
                    }
                }
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override suspend fun readState(): DeviceSnapshot = requestMutex.withLock {
        connect()
        coroutineScope {
            val response =
                async(start = CoroutineStart.UNDISPATCHED) {
                    packets
                        .map { it.getOrThrow() }
                        .first { it.command == A20Protocol.STATE_COMMAND && it.body.isNotEmpty() }
                }
            try {
                val selected = socket ?: throw IOException("Control socket unavailable")
                socketOperation(selected) {
                    selected.outputStream.write(A20Protocol.stateRequest())
                    selected.outputStream.flush()
                }
                A20StateParser.parse(response.await()).let { lastSnapshot ?: it }
            } finally {
                response.cancel()
            }
        }
    }

    override suspend fun readSleepRecords(
        kind: SleepRecordKind,
        expectedSources: Int,
    ): List<SleepSourceRecords> = requestMutex.withLock {
        if (lastSnapshot?.let(ModeCapability::supportsModeControl) != true)
            throw ProtocolException("Sleep history requires A6611 firmware 01.57")
        val selected =
            socket?.takeIf { it.isConnected } ?: throw IOException("Control socket unavailable")
        val collector = SleepRecordCollector(kind, expectedSources)
        coroutineScope {
            val response =
                async(start = CoroutineStart.UNDISPATCHED) {
                    packets
                        .map { it.getOrThrow() }
                        .first {
                            it.command == kind.command && collector.accept(it)
                        }
                }
            try {
                socketOperation(selected) {
                    selected.outputStream.write(SleepHistoryProtocol.request(kind))
                    selected.outputStream.flush()
                }
                // A linked peer may be unavailable. Only complete sources may be retained.
                withTimeoutOrNull(6_000) { response.await() }
                collector.finish()
            } finally {
                response.cancel()
            }
        }
    }

    /** Cancellation closes the descriptor; interrupting a Java thread alone is insufficient. */
    private suspend fun socketOperation(selected: BluetoothSocket, operation: () -> Unit) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val worker = scope.launch {
                try {
                    operation()
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
            continuation.invokeOnCancellation {
                runCatching { selected.close() }
                worker.cancel()
            }
        }

    override suspend fun setMode(mode: Mode) =
        writeSetting(A20Protocol.MODE_COMMAND, A20Protocol.modeRequest(mode))

    override suspend fun setSleepVolume(level: Int) {
        if (lastSnapshot?.mode != Mode.SLEEP || lastSnapshot?.sleepVolume == null)
            throw ProtocolException("Sleep volume is unavailable")
        writeSetting(A20Protocol.SLEEP_VOLUME_COMMAND, A20Protocol.sleepVolumeRequest(level))
    }

    override suspend fun setSleepPlayback(slot: Int, playing: Boolean) {
        if (
            lastSnapshot?.mode != Mode.SLEEP ||
                lastSnapshot?.sleepSoundSlot != slot ||
                lastSnapshot?.sleepSlots?.contains(slot) != true
        )
            throw ProtocolException("Sleep playback is unavailable")
        writeSetting(
            A20Protocol.SLEEP_PLAYBACK_COMMAND,
            A20Protocol.sleepPlaybackRequest(slot, playing),
        )
    }

    override suspend fun setFind(left: Boolean, right: Boolean) =
        writeSetting(FindProtocol.SET, FindProtocol.request(left, right), true)

    private suspend fun writeSetting(
        command: Command,
        request: ByteArray,
        strictAck: Boolean = false,
    ): Unit = requestMutex.withLock {
        if (!allowModeWrites) throw ProtocolException(ModeCapability.REASON)
        if (lastSnapshot?.let { ModeCapability.supportsModeControl(it) } != true)
            throw ProtocolException("Mode control identity/firmware not supported")
        // A write must use the same live socket as its fresh pre-read. No reconnect/replay.
        val selected =
            socket?.takeIf { it.isConnected } ?: throw IOException("Control socket unavailable")
        coroutineScope {
            val ack =
                async(start = CoroutineStart.UNDISPATCHED) {
                    packets.map { it.getOrThrow() }.first { it.command == command }
                }
            try {
                socketOperation(selected) {
                    selected.outputStream.write(request)
                    selected.outputStream.flush()
                }
                // Missing acknowledgement is ambiguous, so the coordinator still reads back.
                val received = withTimeoutOrNull(1_000) { ack.await() }
                if (strictAck && received == null)
                    throw IOException("Find acknowledgement unavailable")
                if (received != null) {
                    if (!received.responseSuccess) throw ModeRejectedException()
                    if (received.body.isNotEmpty())
                        throw ProtocolException("Unexpected setting acknowledgement")
                }
            } finally {
                ack.cancel()
            }
        }
    }

    fun close() {
        ++generation
        val old = socket
        socket = null
        lastSnapshot = null
        runCatching { old?.close() }
        reader?.cancel()
        reader = null
    }
}
