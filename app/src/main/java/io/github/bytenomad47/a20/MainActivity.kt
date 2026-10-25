// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MissingPermission", "DEPRECATION")

package io.github.bytenomad47.a20

import android.Manifest
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.bluetooth.BluetoothManager
import android.companion.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*
import io.github.bytenomad47.a20.widgets.BatteriesWidget
import io.github.bytenomad47.a20.widgets.ControlsWidget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    private val repo
        get() = a20.repository

    private var deviceDialog by mutableStateOf<DeviceDialog?>(null)
    private var readyAfterAssociation = false
    private val nearby =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) chooseDevice()
            else message("Nearby devices access is needed to read your A20.")
        }
    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) beginReady()
            else message("Ready mode stays off. You can still read status with the app open.")
        }
    private val association =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            repo.associationChanged()
            if (Build.VERSION.SDK_INT < 33 && readyAfterAssociation && it.resultCode == RESULT_OK)
                finishReady()
            else if (it.resultCode != RESULT_OK) {
                readyAfterAssociation = false
                message("Companion association cancelled. Ready mode is off.")
            }
        }
    private val saveReport =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null)
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        contentResolver.openOutputStream(uri)?.use {
                            it.write(repo.sanitizedReport().toByteArray())
                        } ?: error("No output stream")
                        withContext(Dispatchers.Main) {
                            message("Diagnostic report saved locally.")
                        }
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            message("The report could not be saved. Try another destination.")
                        }
                    }
                }
        }

    private var repairExport: java.io.File? = null
    private val saveRepairReport =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri
            ->
            val file = repairExport
            if (uri != null && file != null)
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = runCatching {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            file.inputStream().use { it.copyTo(output) }
                        } ?: error("No output stream")
                    }
                        .isSuccess
                    withContext(Dispatchers.Main) {
                        message(
                            if (success) "Repair report exported."
                            else "Could not export the report."
                        )
                    }
                }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Older pinned shortcuts may still target MainActivity until the launcher refreshes.
        // Forward before creating the dashboard, and never replay a restored request.
        val shortcut = LauncherShortcuts.consume(intent)
        if (shortcut != null) {
            if (savedInstanceState == null) startActivity(LauncherShortcuts.intent(this, shortcut))
            finish()
            return
        }
        savedInstanceState
            ?.getString("repair_export")
            ?.takeIf { it.matches(Regex("[a-f0-9-]{36}\\.json")) }
            ?.let {
                repairExport = java.io.File(filesDir, "charging_reports/$it")
            }
        enableEdgeToEdge()
        readyAfterAssociation = savedInstanceState?.getBoolean("pending_ready") ?: false
        setContent {
            A20App(
                repo,
                onChoose = { chooseDevice() },
                onRefresh = { refresh() },
                onAction = { perform(it) },
                onReady = ::changeReady,
                onAutomation = { value -> lifecycleScope.launch { repo.setAutomation(value) } },
                onTheme = { value -> lifecycleScope.launch { repo.setTheme(value) } },
                onAddTile = ::addTile,
                onExport = { saveReport.launch("a20-companion-report.txt") },
                onBatterySettings = {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                },
                onAddBatteryWidget = { pinWidget(BatteriesWidget::class.java) },
                onAddControlsWidget = { pinWidget(ControlsWidget::class.java) },
                initialPage =
                    if (intent.getBooleanExtra("open_repair", false)) Page.REPAIR else Page.HOME,
                repairContent = { back ->
                    GuidedRepairPage(
                        a20.repair,
                        back,
                        onReset = { token, acknowledgeUnknown ->
                            RepairService.start(
                                this,
                                reset = true,
                                token = token,
                                acknowledgeUnknown = acknowledgeUnknown,
                            )
                        },
                        onCheck = { RepairService.start(this, reset = false) },
                        onExport = { file ->
                            repairExport = file
                            saveRepairReport.launch("a20-charging-report.json")
                        },
                    )
                },
                dialogs = {
                    DeviceDialogs(
                        deviceDialog,
                        onDismiss = { deviceDialog = null },
                        onShowOther = { chooseDevice(true) },
                        onChoose = { deviceDialog = DeviceDialog.Confirm(it) },
                        onConfirm = ::useDevice,
                        onForget = ::confirmForget,
                        onRequestForget = ::forget,
                        onBluetoothSettings = {
                            deviceDialog = null
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        },
                    )
                },
            )
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.awaitInitialized()
                a20.refreshAudioConnection()
                while (isActive) {
                    if (
                        repo.settings.value.address != null && permitted() && !repo.state.value.busy
                    )
                        refresh()
                    delay(60_000)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val shortcut = LauncherShortcuts.consume(intent) ?: return
        startActivity(LauncherShortcuts.intent(this, shortcut))
    }

    override fun onStart() {
        super.onStart()
        repo.setVisible(true)
    }

    override fun onStop() {
        repo.setVisible(false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_ready", readyAfterAssociation)
        outState.putString("repair_export", repairExport?.name)
        super.onSaveInstanceState(outState)
    }

    private fun permitted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun message(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun perform(action: DeviceAction) {
        if (!permitted()) {
            nearby.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        if (repo.state.value.readyMode) ActionService.start(this, action)
        else lifecycleScope.launch { repo.execute(action) }
    }

    private fun refresh() {
        perform(DeviceAction.Refresh)
    }

    private fun chooseDevice(showAll: Boolean = false) {
        if (!permitted()) {
            nearby.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        if (repo.state.value.busy) {
            message("Wait for the current A20 action to finish.")
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val paired =
            if (adapter?.isEnabled == true) adapter.bondedDevices.sortedBy { it.name ?: "" }
            else emptyList()
        val likely = paired.filter { it.name?.contains("A20", ignoreCase = true) == true }
        val devices = if (showAll || likely.isEmpty()) paired else likely
        deviceDialog =
            DeviceDialog.Choose(
                devices.map { PairedChoice(it.address, it.name ?: "Paired Bluetooth device") },
                !showAll && likely.isNotEmpty() && likely.size != paired.size,
                repo.settings.value.address?.let {
                    PairedChoice(it, repo.settings.value.name ?: "Sleep A20")
                },
            )
    }

    private fun useDevice(device: PairedChoice) {
        deviceDialog = null
        lifecycleScope.launch {
            try {
                repo.select(device.address, device.name)
                a20.refreshAudioConnection()
                refresh()
            } catch (_: Exception) {
                message("Wait for the current action to finish, then choose again.")
            }
        }
    }

    private fun changeReady(enabled: Boolean) {
        if (!enabled) {
            lifecycleScope.launch {
                repo.setReady(false)
                stopObserving()
                stopService(Intent(this@MainActivity, ActionService::class.java))
            }
            return
        }
        if (repo.settings.value.address == null) {
            message("Choose your A20 first.")
            return
        }
        if (!permitted()) {
            nearby.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else beginReady()
    }

    private fun beginReady() {
        repo.associationChanged()
        if (repo.state.value.associated) {
            finishReady()
            return
        }
        val address = repo.settings.value.address ?: return
        val manager =
            getSystemService(CompanionDeviceManager::class.java)
                ?: run {
                    message("Companion setup is unavailable on this phone.")
                    return
                }
        val filter = BluetoothDeviceFilter.Builder().setAddress(address).build()
        val request =
            AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        readyAfterAssociation = true
        try {
            manager.associate(
                request,
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(sender: IntentSender) {
                        association.launch(IntentSenderRequest.Builder(sender).build())
                    }

                    override fun onDeviceFound(sender: IntentSender) {
                        association.launch(IntentSenderRequest.Builder(sender).build())
                    }

                    override fun onAssociationCreated(info: AssociationInfo) {
                        repo.associationChanged()
                        if (readyAfterAssociation) finishReady()
                    }

                    override fun onFailure(error: CharSequence?) {
                        readyAfterAssociation = false
                        message("Companion setup failed. Ready mode stays off.")
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (_: Exception) {
            readyAfterAssociation = false
            message("Companion setup is unavailable. Use the app or Quick Settings manually.")
        }
    }

    private fun finishReady() {
        readyAfterAssociation = false
        lifecycleScope.launch {
            try {
                val manager = getSystemService(CompanionDeviceManager::class.java)
                val address = repo.settings.value.address ?: return@launch
                if (Build.VERSION.SDK_INT >= 36) {
                    val info =
                        manager.myAssociations.firstOrNull {
                            it.deviceMacAddress?.toString().equals(address, true)
                        } ?: error("Association missing")
                    manager.startObservingDevicePresence(
                        ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build()
                    )
                } else manager.startObservingDevicePresence(address)
                repo.setReady(true)
                repo.settings.first { it.ready }
                repo.associationChanged()
                ActionService.start(this@MainActivity, DeviceAction.Refresh)
            } catch (_: Exception) {
                repo.setReady(false)
                message("Device presence could not be enabled. Ready mode stays off.")
            }
        }
    }

    private fun stopObserving() {
        runCatching {
            val manager = getSystemService(CompanionDeviceManager::class.java)
            val address = repo.settings.value.address ?: return
            if (Build.VERSION.SDK_INT >= 36)
                manager.myAssociations
                    .filter { it.deviceMacAddress?.toString().equals(address, true) }
                    .forEach {
                        manager.stopObservingDevicePresence(
                            ObservingDevicePresenceRequest.Builder().setAssociationId(it.id).build()
                        )
                    }
            else manager.stopObservingDevicePresence(address)
        }
    }

    private fun forget() {
        if (repo.state.value.busy) {
            message("Wait for the current A20 action to finish, then try again.")
            return
        }
        deviceDialog = DeviceDialog.Forget
    }

    private fun confirmForget() {
        if (repo.state.value.busy) {
            message("Wait for the current A20 action to finish, then try again.")
            return
        }
        deviceDialog = null
        lifecycleScope.launch {
            stopObserving()
            stopService(Intent(this@MainActivity, ActionService::class.java))
            repo.forget()
        }
    }

    private fun pinWidget(provider: Class<out AppWidgetProvider>) {
        val manager = AppWidgetManager.getInstance(this)
        if (manager.isRequestPinAppWidgetSupported) {
            runCatching { manager.requestPinAppWidget(ComponentName(this, provider), null, null) }
                .onFailure {
                    message("Long-press your home screen, choose Widgets, then A20 Companion.")
                }
        } else message("Long-press your home screen, choose Widgets, then A20 Companion.")
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33)
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, ModeTileService::class.java),
                "A20 Sleep mode",
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_moon),
                mainExecutor,
            ) {}
        else message("Open Quick Settings, choose Edit, then add the A20 tile.")
    }
}
