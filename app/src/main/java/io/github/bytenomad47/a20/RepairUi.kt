// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.bytenomad47.a20

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bytenomad47.a20.repair.*
import java.io.File
import java.text.DateFormat
import java.util.Date

internal fun attemptDescription(attempt: ManualAttempt): String {
    val whenText =
        attempt.at?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        } ?: "time unavailable"
    val outcome =
        when (attempt.outcome) {
            "acknowledged" -> "accepted by earbud"
            "verified-zero" -> "zero previously verified"
            "recorded" -> "reported previously"
            else -> "outcome unknown"
        }
    return "Last attempt: $whenText · $outcome"
}

@Composable
internal fun GuidedRepairPage(
    controller: RepairController,
    onBack: () -> Unit,
    onReset: (String, Boolean) -> Unit,
    onCheck: () -> Unit,
    onExport: (File) -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(controller) { controller.restoreLocalOutcome() }
    var advanced by rememberSaveable { mutableStateOf(false) }
    // Rotation/process recreation closes consent. A new dialog always gets a new request token.
    var review by remember { mutableStateOf<ResetReview?>(null) }
    var understood by remember { mutableStateOf(false) }
    var uncertain by remember { mutableStateOf(false) }
    val side = if (state.ear == "L") "left" else "right"
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ Settings") }
        Text(
            "Charging repair",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Original Sleep A20 (A6611) · firmware 01.57",
            style = MaterialTheme.typography.labelLarge,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("L" to "Left", "R" to "Right").forEach { (ear, label) ->
                val selected = state.ear == ear
                FilledTonalButton(
                    onClick = { controller.select(ear) },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f).semantics { this.selected = selected },
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor =
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer
                        ),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painterResource(R.drawable.sleep_a20_bud),
                            null,
                            Modifier.size(64.dp).graphicsLayer {
                                scaleX = if (ear == "L") -1f else 1f
                            },
                        )
                        Text(label)
                    }
                }
            }
        }
        Text(
            "Take the $side earbud out. Leave the other in the closed case, then connect the $side earbud to your phone."
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.title, style = MaterialTheme.typography.titleLarge)
                Text(state.explanation)
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.stage)
                }
            }
        }
        state.lastAttempt?.let {
            Text(attemptDescription(it), style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = {
                understood = false
                uncertain = false
                review = controller.reviewReset()
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset $side earbud")
        }
        Text(
            "Works with official firmware. No fault-count check or firmware change is required. Every reset needs your confirmation.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { advanced = !advanced }, enabled = !state.busy) {
            Text(if (advanced) "Hide advanced" else "Advanced")
        }
        if (advanced) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Reading the fault count requires our diagnostic firmware. These checks never reset the earbud."
                    )
                    FilledTonalButton(
                        onClick = onCheck,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Check $side fault count")
                    }
                    OutlinedButton(
                        onClick = { state.report?.let(onExport) },
                        enabled = !state.busy && state.report != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Export latest report")
                    }
                    if (state.details.isNotEmpty())
                        Text(state.details, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    review?.let { consent ->
        val chosen = if (consent.ear == "L") "left" else "right"
        AlertDialog(
            onDismissRequest = {
                review = null
                controller.dismissReview()
            },
            title = { Text("Reset $chosen charging records?") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Confirm this is the original A6611 and only the $chosen earbud is connected. This erases its saved charging faults and history and may restore a higher charging target. It does not repair a worn battery."
                    )
                    Text(
                        "Do not proceed if the earbud is unusually hot or damaged. Keep the next charge supervised. The earbud can acknowledge a reset without proving its main count is zero."
                    )
                    consent.previous?.let { previous ->
                        Text(attemptDescription(previous))
                        Text(
                            "You can make another manual attempt. A returning count of 1 or 2 is below the examined charging-restriction threshold and does not itself indicate another reset is needed."
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(understood, { understood = it })
                        Text("I understand and want to reset this earbud.", Modifier.weight(1f))
                    }
                    if (consent.previous?.outcome == "unknown") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(uncertain, { uncertain = it })
                            Text(
                                "The earlier attempt may already have worked. I am deliberately starting a new attempt.",
                                Modifier.weight(1f),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        review = null
                        onReset(consent.token, uncertain)
                    },
                    enabled =
                        understood &&
                            !state.busy &&
                            (consent.previous?.outcome != "unknown" || uncertain),
                ) {
                    Text("Reset $chosen")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        review = null
                        controller.dismissReview()
                    }
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}
