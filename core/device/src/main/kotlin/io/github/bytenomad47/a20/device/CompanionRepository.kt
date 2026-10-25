// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MissingPermission")

package io.github.bytenomad47.a20.device

import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.content.Context
import android.os.Build
import io.github.bytenomad47.a20.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide state shared by UI, tile, companion service and explicit intents. */
class CompanionRepository(
    private val context: Context,
    private val gatewayOverride: DeviceGateway? = null,
    private val appVersion: String = "development",
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = DeviceStore(context)
    private val initialized = CompletableDeferred<Unit>()
    private val actionMutex = Mutex()
    private val findIo = Mutex()
    private var findOwnsLock = false
    private var playbackVisible = false
    var findPending = false
        private set

    var onFindStopped: (() -> Unit)? = null
    var onFindDisconnected: (() -> Unit)? = null
    private val stateMutable = MutableStateFlow(CompanionState())
    val state = stateMutable.asStateFlow()
    val settings = store.settings.stateIn(scope, SharingStarted.Eagerly, UserSettings())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val transport = adapter?.let {
        RfcommTransport(it, { settings.value.address }, allowModeWrites = ModeCapability.VERIFIED)
    }
    private val modeAttempts = MutableStateFlow<List<String>>(emptyList())
    private val coordinator =
        ActionCoordinator(
            object : DeviceGateway {
                override suspend fun readState(): DeviceSnapshot {
                    val parsed =
                        (gatewayOverride ?: transport)?.readState()
                            ?: throw java.io.IOException("Bluetooth unavailable")
                    accept(parsed)
                    return parsed
                }

                override suspend fun setSleepVolume(level: Int) {
                    (gatewayOverride ?: transport)?.setSleepVolume(level)
                        ?: throw java.io.IOException("Bluetooth unavailable")
                }

                override suspend fun setSleepPlayback(slot: Int, playing: Boolean) {
                    (gatewayOverride ?: transport)?.setSleepPlayback(slot, playing)
                        ?: throw java.io.IOException("Bluetooth unavailable")
                }

                override suspend fun setMode(mode: Mode) {
                    (gatewayOverride ?: transport)?.setMode(mode)
                        ?: throw java.io.IOException("Bluetooth unavailable")
                }
            }
        )
    var onStateChanged: (() -> Unit)? = null
    var visible = false
        private set

    var audioConnection = AudioConnection.UNKNOWN
        private set

    init {
        scope.launch {
            try {
                val prefs = store.settings.first()
                val cache = store.batteries.first()
                stateMutable.value =
                    stateMutable.value.copy(
                        selectedName = prefs.name,
                        left = cache[0],
                        right = cache[1],
                        case = cache[2],
                        readyMode = prefs.ready,
                        automationEnabled = prefs.automation,
                        theme = prefs.theme,
                        message =
                            if (prefs.address == null) "Choose your paired A20 to get started."
                            else "Ready to read your A20. Saved readings are last known.",
                    )
                settings.first { it == prefs }
                initialized.complete(Unit)
                store.settings.collect { p ->
                    update {
                        it.copy(
                            selectedName = p.name,
                            readyMode = p.ready,
                            automationEnabled = p.automation,
                            theme = p.theme,
                            associated = hasAssociation(p.address),
                        )
                    }
                    if (!p.ready && !visible && !state.value.busy) release()
                }
            } catch (_: Exception) {
                initialized.complete(Unit)
                update {
                    it.copy(
                        message =
                            "Local settings could not be read. Restart the app before using controls."
                    )
                }
            }
        }
        transport?.onFindStopped = { scope.launch { onFindStopped?.invoke() } }
        transport?.onDisconnect = {
            scope.launch { onFindDisconnected?.invoke() }
            scope.launch {
                update {
                    it.copy(
                        connection = Connection.DISCONNECTED,
                        message =
                            if (it.busy) it.message
                            else "Control connection ended. Last reported readings are retained.",
                    )
                }
            }
        }
        scope.launch {
            transport?.events?.collect { value ->
                if (!state.value.busy && transport.connected) accept(value)
            }
        }
        scope.launch {
            (gatewayOverride ?: transport)?.playbackEvents?.collect { status ->
                update { previous ->
                    val changed = previous.snapshot?.let { SleepPlaybackProtocol.apply(it, status) }
                    if (changed == null) previous else previous.copy(snapshot = changed)
                }
            }
        }
    }

    private fun update(change: (CompanionState) -> CompanionState) {
        stateMutable.update(change)
        onStateChanged?.invoke()
    }

    private fun hasAssociation(address: String?): Boolean {
        if (address == null) return false
        return runCatching {
                val manager = context.getSystemService(CompanionDeviceManager::class.java)
                if (Build.VERSION.SDK_INT >= 33)
                    manager.myAssociations.any {
                        it.deviceMacAddress?.toString().equals(address, true)
                    }
                else manager.associations.any { it.equals(address, true) }
            }
            .getOrDefault(false)
    }

    fun associationChanged() {
        update { it.copy(associated = hasAssociation(settings.value.address)) }
    }

    private suspend fun accept(value: DeviceSnapshot) {
        update { it.withSnapshot(value, System.currentTimeMillis()) }
        store.cache(state.value)
        if (value.mode == Mode.SLEEP && !state.value.busy && !playbackVisible) release()
    }

    suspend fun awaitInitialized() {
        initialized.await()
    }

    suspend fun execute(action: DeviceAction): ActionResult {
        initialized.await()
        if (findPending && action != DeviceAction.Refresh)
            return ActionResult(ActionCode.BUSY, "Stop Find earbuds before another action.")
        if (!actionMutex.tryLock())
            return ActionResult(ActionCode.BUSY, "An A20 action is already running.")
        try {
            if (settings.value.address == null)
                return ActionResult(
                        ActionCode.UNAVAILABLE,
                        "Choose your paired A20 in the app first.",
                    )
                    .also { r -> update { it.copy(message = r.message) } }
            update {
                it.copy(
                    busy = true,
                    connection =
                        if (action == DeviceAction.Refresh) Connection.CONNECTING
                        else it.connection,
                    message =
                        if (action == DeviceAction.Refresh) "Reading A20…"
                        else "Checking mode control…",
                )
            }
            val result = coordinator.execute(action)
            if (action != DeviceAction.Refresh) {
                val requested =
                    when (action) {
                        is DeviceAction.SetMode -> action.mode.wireName
                        is DeviceAction.SetSleepVolume -> "sleep-volume-${action.level}"
                        DeviceAction.ToggleSleepPlayback -> "sleep-playback-toggle"
                        else -> "toggle"
                    }
                val entry =
                    "${System.currentTimeMillis()} request=$requested result=${result.code} reported=${state.value.snapshot?.mode} sleepVolume=${state.value.snapshot?.sleepVolume} sleepPlaying=${state.value.snapshot?.sleepPlaying} detail=${result.message}"
                modeAttempts.update { (it + entry).takeLast(8) }
            }
            if (
                !result.successful &&
                    result.code != ActionCode.UNVERIFIED &&
                    result.code != ActionCode.BUSY
            )
                transport?.close()
            update {
                it.copy(
                    message = result.message,
                    connection =
                        when {
                            result.code == ActionCode.UNVERIFIED -> it.connection
                            result.successful && it.connection == Connection.CONNECTED ->
                                Connection.CONNECTED
                            else -> Connection.ERROR
                        },
                )
            }
            return result
        } catch (e: CancellationException) {
            release()
            throw e
        } finally {
            update { it.copy(busy = false) }
            // Sleep notifications are held only for the visible Home controls.
            if (state.value.snapshot?.mode == Mode.SLEEP) {
                if (!playbackVisible) release()
            } else if (
                !visible && (!state.value.readyMode || state.value.snapshot?.mode != Mode.BLUETOOTH)
            ) {
                release()
            }
            actionMutex.unlock()
        }
    }

    fun restoreFindPending(value: Boolean) {
        findPending = value
    }

    suspend fun beginFind(left: Boolean, right: Boolean) = findIo.withLock {
        initialized.await()
        if (!actionMutex.tryLock())
            throw FindNotSentException("Another A20 action is running; find was not started.")
        findOwnsLock = true
        var attempted = false
        try {
            val gateway = gatewayOverride ?: transport ?: error("Bluetooth unavailable")
            val fresh = withTimeout(4000) { gateway.readState() }
            accept(fresh)
            check(ModeCapability.supportsModeControl(fresh)) {
                "Find requires known A6611 firmware01.57."
            }
            check(!left || fresh.primary == Ear.LEFT || fresh.peerLinked == true) {
                "Left earbud unavailable"
            }
            check(!right || fresh.primary == Ear.RIGHT || fresh.peerLinked == true) {
                "Right earbud unavailable"
            }
            findPending = true
            update { it.copy(busy = true, message = "Find sound requested") }
            attempted = true
            gateway.setFind(left, right)
        } catch (e: Exception) {
            if (!attempted) {
                finishFindOwnership(false)
                if (e !is CancellationException)
                    throw FindNotSentException(
                        "Find was not started. Check connection and firmware01.57."
                    )
            }
            throw e
        }
    }

    suspend fun endFind() = findIo.withLock {
        initialized.await()
        if (!findOwnsLock) {
            actionMutex.lock()
            findOwnsLock = true
        }
        var accepted = false
        try {
            val gateway = gatewayOverride ?: transport ?: error("Bluetooth unavailable")
            val fresh = withTimeout(4000) { gateway.readState() }
            accept(fresh)
            check(ModeCapability.supportsModeControl(fresh)) { "Unknown find firmware" }
            gateway.setFind(false, false)
            accepted = true
        } finally {
            finishFindOwnership(accepted)
        }
    }

    suspend fun finishFindNotification() = findIo.withLock { finishFindOwnership(true) }

    suspend fun releaseFindOnDisconnect() = findIo.withLock { finishFindOwnership(false) }

    private fun finishFindOwnership(clear: Boolean) {
        if (clear) findPending = false
        if (findOwnsLock) {
            findOwnsLock = false
            actionMutex.unlock()
            update { it.copy(busy = false) }
            // Returning Home may precede the asynchronous Find stop acknowledgement.
            // Keep its now-idle Sleep channel for Home's playback notifications.
            if (!clear || state.value.snapshot?.mode != Mode.SLEEP || !playbackVisible) release()
        }
    }

    /** Shares the normal action lock. Repair never competes with a widget or Ready mode. */
    suspend fun <T> withRepairLease(block: suspend (String, DeviceSnapshot) -> T): T {
        initialized.await()
        check(!findPending) { "Stop Find earbuds before charging repair." }
        check(actionMutex.tryLock()) {
            "Another A20 action is running. Try again when it finishes."
        }
        try {
            val address = settings.value.address ?: error("Choose your A20 first.")
            update { it.copy(busy = true, message = "Checking charging records…") }
            val fresh =
                withTimeout(10_000) {
                    (gatewayOverride ?: transport)?.readState() ?: error("Bluetooth unavailable")
                }
            accept(fresh)
            release()
            return block(address, fresh)
        } finally {
            release()
            update { it.copy(busy = false) }
            actionMutex.unlock()
        }
    }

    suspend fun select(address: String, name: String) {
        initialized.await()
        if (state.value.busy) throw IllegalStateException("Wait for the current action")
        val old = settings.value.address
        release()
        if (old != null && !old.equals(address, true))
            runCatching {
                context.getSystemService(CompanionDeviceManager::class.java).disassociate(old)
            }
        store.select(address, name)
        modeAttempts.value = emptyList()
        update {
            CompanionState(
                selectedName = name,
                message = "A20 selected. Pull down to refresh status.",
                theme = it.theme,
            )
        }
        // Wait for settings flow before a caller can issue a refresh.
        settings.first { it.address == address }
    }

    suspend fun forget() {
        if (state.value.busy) return
        val old = settings.value.address
        release()
        if (old != null)
            runCatching {
                context.getSystemService(CompanionDeviceManager::class.java).disassociate(old)
            }
        store.forget()
        modeAttempts.value = emptyList()
        update { CompanionState() }
    }

    suspend fun setReady(enabled: Boolean) {
        store.ready(enabled)
    }

    suspend fun setAutomation(enabled: Boolean) {
        store.automation(enabled)
    }

    suspend fun setTheme(choice: ThemeChoice) {
        store.theme(choice)
    }

    /** Visible Home only: one read to attach notifications, never a periodic poll or write. */
    fun setPlaybackVisible(value: Boolean) {
        if (playbackVisible == value) return
        playbackVisible = value
        if (!value) {
            if (
                !state.value.busy &&
                    state.value.snapshot?.mode == Mode.SLEEP &&
                    state.value.connection == Connection.CONNECTED
            )
                release()
        } else if (
            !state.value.busy &&
                state.value.phone == PhoneConnection.CONNECTED &&
                state.value.snapshot?.mode == Mode.SLEEP &&
                state.value.connection != Connection.CONNECTED
        ) {
            scope.launch { execute(DeviceAction.Refresh) }
        }
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (
            !value &&
                !state.value.busy &&
                (!state.value.readyMode || state.value.snapshot?.mode != Mode.BLUETOOTH)
        )
            release()
    }

    fun setAudio(value: AudioConnection) {
        audioConnection = value
        update {
            it.copy(
                audio = value,
                phone =
                    if (value == AudioConnection.CONNECTED) PhoneConnection.CONNECTED else it.phone,
            )
        }
    }

    fun setPhone(value: PhoneConnection) {
        update { it.copy(phone = value) }
    }

    fun release() {
        if (findOwnsLock) return
        transport?.close()
        update {
            it.copy(
                connection =
                    if (it.connection == Connection.ERROR) Connection.ERROR
                    else if (it.selectedName == null) Connection.IDLE else Connection.STANDBY
            )
        }
    }

    fun disconnected() {
        transport?.close()
        update {
            it.copy(
                connection = Connection.DISCONNECTED,
                phone = PhoneConnection.DISCONNECTED,
                audio = AudioConnection.DISCONNECTED,
                message = "A20 disconnected. Last reported readings are retained.",
            )
        }
    }

    fun sanitizedReport(): String {
        val s = state.value
        val v = s.snapshot
        return buildString {
            appendLine("A20 Companion $appVersion")
            appendLine(
                "Normal battery and mode report. Charging repair has a separate local report."
            )
            appendLine("Normal control UUID: ${A20Protocol.SERVICE_UUID}")
            appendLine("Last received state body bytes: ${transport?.lastStateBodyBytes}")
            appendLine(
                "State layout: ${A20StateParser.KNOWN_BODY_SIZE} bytes (observed on A6611/01.57)"
            )
            appendLine("Phone: ${s.phone}; control: ${s.connection}; audio: ${s.audio}")
            appendLine("Last state received at (phone clock): ${s.stateReceivedAtMs}")
            appendLine("Host: ${v?.primary}; peer linked: ${v?.peerLinked}; mode: ${v?.mode}")
            appendLine("Left: ${s.left.percent}, received at ${s.left.receivedAtMs}")
            appendLine("Right: ${s.right.percent}, received at ${s.right.receivedAtMs}")
            appendLine("Case: ${s.case.percent}, received at ${s.case.receivedAtMs}")
            appendLine("Sleep volume raw (0..65): ${v?.sleepVolume}")
            appendLine(
                "Sleep sound slot: ${v?.sleepSoundSlot}; playing: ${v?.sleepPlaying}; available slots: ${v?.sleepSlots}"
            )
            appendLine("Firmware left/right: ${v?.leftFirmware}/${v?.rightFirmware}")
            appendLine(
                "Ready preference: ${s.readyMode}; external automation: ${s.automationEnabled}"
            )
            appendLine("Mode writes qualified: ${ModeCapability.VERIFIED}")
            appendLine(
                "Mode firmware: ${ModeCapability.QUALIFIED_FIRMWARE}; active earbud and any linked peer required"
            )
            modeAttempts.value.forEach { appendLine("Mode attempt: $it") }
            appendLine("No address, device name, serial, account data or raw packets included.")
        }
    }
}
