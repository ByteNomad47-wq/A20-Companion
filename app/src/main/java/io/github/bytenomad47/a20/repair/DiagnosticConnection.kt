// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MissingPermission")

package io.github.bytenomad47.a20.repair

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal interface DiagnosticConnection : ChargingProtocol.Link, Closeable {
    fun connect()
}

/** Secure SDP-discovered SPP only. No channel fallback, firmware upload or arbitrary commands. */
internal class SppDiagnosticConnection(adapter: BluetoothAdapter, address: String) :
    DiagnosticConnection {
    private val closed = AtomicBoolean(false)
    private val socket: BluetoothSocket =
        adapter
            .getRemoteDevice(address)
            .createRfcommSocketToServiceRecord(
                UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
            )
    private val deadline = SystemClock.elapsedRealtime() + 40_000L

    override fun connect() {
        socket.connect()
    }

    override fun send(value: Int) {
        check(!cancelled()) { "Diagnostic session ended." }
        require(value == '4'.code || value == '7'.code || value == '2'.code || value == '3'.code)
        socket.outputStream.write(value)
        socket.outputStream.flush()
    }

    override fun available() = socket.inputStream.available()

    override fun read(bytes: ByteArray, count: Int) = socket.inputStream.read(bytes, 0, count)

    override fun now() = SystemClock.elapsedRealtime()

    override fun pause(ms: Long) {
        if (cancelled()) throw IOException("Diagnostic session ended.")
        Thread.sleep(ms)
    }

    override fun cancelled() =
        closed.get() || Thread.currentThread().isInterrupted || now() >= deadline

    override fun close() {
        closed.set(true)
        runCatching { socket.close() }
    }
}
