// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MissingPermission")

package io.github.bytenomad47.a20

import android.app.Application
import android.bluetooth.*
import android.content.*
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import io.github.bytenomad47.a20.device.*

class A20Application : Application() {
    lateinit var repository: CompanionRepository
        private set

    internal lateinit var repair: io.github.bytenomad47.a20.repair.RepairController
        private set

    internal lateinit var find: io.github.bytenomad47.a20.find.FindSession
        private set

    override fun onCreate() {
        super.onCreate()
        repository =
            CompanionRepository(
                this,
                appVersion = BuildConfig.VERSION_NAME,
            )
        repair = io.github.bytenomad47.a20.repair.RepairController(this, repository)
        find = io.github.bytenomad47.a20.find.createFindSession(this, repository)
        Notifications.channels(this)
        io.github.bytenomad47.a20.widgets.WidgetUpdates.observe(this)
        repository.onStateChanged = { ModeTileService.requestUpdate(this) }
        val events =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                        val device =
                            androidx.core.content.IntentCompat.getParcelableExtra(
                                intent,
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java,
                            )
                        if (
                            runCatching {
                                    device?.address?.let { it == repository.settings.value.address }
                                }
                                .getOrNull() == true
                        )
                            find.reconnect()
                    }
                    if (
                        intent.action == Intent.ACTION_TIME_CHANGED ||
                            intent.action == Intent.ACTION_TIMEZONE_CHANGED
                    ) {
                        io.github.bytenomad47.a20.widgets.WidgetUpdates.refresh(
                            this@A20Application
                        )
                        return
                    }
                    if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                        if (
                            intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) ==
                                BluetoothAdapter.STATE_OFF
                        )
                            repository.disconnected()
                        return
                    }
                    val device =
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            ?: return
                    val selected = repository.settings.value.address ?: return
                    if (
                        runCatching { device.address.equals(selected, true) }
                            .getOrDefault(false)
                            .not()
                    )
                        return
                    when (intent.action) {
                        BluetoothDevice.ACTION_ACL_CONNECTED ->
                            repository.setPhone(PhoneConnection.CONNECTED)
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> repository.disconnected()
                        BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ->
                            repository.setAudio(
                                when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                                    BluetoothProfile.STATE_CONNECTED -> AudioConnection.CONNECTED
                                    BluetoothProfile.STATE_DISCONNECTED ->
                                        AudioConnection.DISCONNECTED
                                    else -> AudioConnection.UNKNOWN
                                }
                            )
                    }
                }
            }
        ContextCompat.registerReceiver(
            this,
            events,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        io.github.bytenomad47.a20.widgets.WidgetUpdates.refresh(this)
    }

    fun refreshAudioConnection() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        runCatching {
            if (!adapter.isEnabled) {
                repository.disconnected()
                return
            }
            adapter.getProfileProxy(
                this,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        try {
                            val selected = repository.settings.value.address
                            repository.setAudio(
                                if (selected == null) AudioConnection.UNKNOWN
                                else if (
                                    proxy.connectedDevices.any { it.address.equals(selected, true) }
                                )
                                    AudioConnection.CONNECTED
                                else AudioConnection.DISCONNECTED
                            )
                        } catch (_: SecurityException) {
                            repository.setAudio(AudioConnection.UNKNOWN)
                        } finally {
                            adapter.closeProfileProxy(profile, proxy)
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {}
                },
                BluetoothProfile.A2DP,
            )
        }
    }
}

val Context.a20: A20Application
    get() = applicationContext as A20Application
