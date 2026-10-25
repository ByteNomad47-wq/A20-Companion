// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Addresses stay in memory/settings; these choices never enter diagnostic reports. */
internal data class PairedChoice(val address: String, val name: String)

internal sealed interface DeviceDialog {
    data class Choose(
        val devices: List<PairedChoice>,
        val showOther: Boolean,
        val selected: PairedChoice? = null,
    ) : DeviceDialog

    data class Confirm(val device: PairedChoice) : DeviceDialog

    data object Forget : DeviceDialog
}

@Composable
internal fun DeviceDialogs(
    dialog: DeviceDialog?,
    onDismiss: () -> Unit,
    onShowOther: () -> Unit,
    onChoose: (PairedChoice) -> Unit,
    onConfirm: (PairedChoice) -> Unit,
    onForget: () -> Unit,
    onRequestForget: () -> Unit = {},
    onBluetoothSettings: () -> Unit = {},
) {
    if (dialog == null) return
    if (dialog is DeviceDialog.Choose) {
        ChooseDeviceDialog(
            dialog,
            onDismiss,
            onShowOther,
            onChoose,
            onRequestForget,
            onBluetoothSettings,
        )
        return
    }
    val confirming = dialog as? DeviceDialog.Confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(32.dp),
        title = { Text(if (confirming != null) "Use this device?" else "Forget this A20?") },
        text = {
            Text(
                if (confirming != null)
                    "${confirming.device.name}\n\nFor original model A6611 only. Companion reads batteries and switches listening modes."
                else
                    "Remove Companion’s saved device, battery readings and background association. The earbuds stay paired in Android."
            )
        },
        confirmButton = {
            if (confirming != null) {
                Button(onClick = { onConfirm(confirming.device) }) { Text("Use device") }
            } else {
                Button(
                    onClick = onForget,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                ) {
                    Text("Forget")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChooseDeviceDialog(
    dialog: DeviceDialog.Choose,
    onDismiss: () -> Unit,
    onShowOther: () -> Unit,
    onChoose: (PairedChoice) -> Unit,
    onRequestForget: () -> Unit,
    onBluetoothSettings: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    "Choose your A20",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Original Sleep A20 · A6611",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dialog.selected?.let { saved ->
                        Surface(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(
                                        start = 16.dp,
                                        end = 8.dp,
                                        top = 12.dp,
                                        bottom = 12.dp,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        saved.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text("Selected", style = MaterialTheme.typography.labelMedium)
                                }
                                IconButton(
                                    onClick = onRequestForget,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_delete),
                                        contentDescription = "Forget ${saved.name} in Companion",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                    }
                    dialog.devices
                        .filter { it.address != dialog.selected?.address }
                        .forEach { device ->
                            Surface(
                                onClick = { onChoose(device) },
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(16.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    if (dialog.devices.isEmpty() && dialog.selected == null) {
                        Text(
                            "No paired devices available. Open Bluetooth settings to pair your A20.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (dialog.showOther) {
                        TextButton(
                            onClick = onShowOther,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text("Other paired devices", modifier = Modifier.weight(1f))
                            Text(
                                "›",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onBluetoothSettings,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) {
                        Text("Bluetooth settings", modifier = Modifier.fillMaxWidth())
                    }
                    FilledTonalButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
