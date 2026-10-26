// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bytenomad47.a20.protocol.SleepRecordKind
import io.github.bytenomad47.a20.sleep.SleepHistoryController
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

@Composable
internal fun SleepHistoryPage(
    controller: SleepHistoryController,
    selected: String?,
    busy: Boolean,
    onBack: () -> Unit,
    onExport: (String) -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, scope) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) scope.coroutineContext.cancelChildren()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            scope.coroutineContext.cancelChildren()
        }
    }
    var details by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(selected) { controller.load() }
    if (confirmDelete)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete local sleep history?") },
            text = {
                Text(
                    "Delete this pair’s imported sleep records from Companion. Records stored on the earbuds are unchanged."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch { controller.deleteLocal() }
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Text(
            "Sleep history",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Preview · saved records",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Your nights, on your phone", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Read the sleep records already stored on your earbuds. This preview keeps them locally without erasing the originals."
                )
                Text(
                    "Sleep times and stages are still being validated. Charts and sleep scores will follow once those readings are confirmed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Button(
            onClick = { scope.launch { controller.sync() } },
            enabled = selected != null && !busy && !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.loading) "Reading sleep history…" else "Sync sleep records")
        }
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (selected == null) Text("Choose your A20 on Home first.")
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.syncedAt > 0) {
            Text(
                "Last sync: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.syncedAt))}",
                style = MaterialTheme.typography.bodySmall,
            )
            for (source in 0..1) {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (source == 0) "Left earbud" else "Right earbud",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${state.records.count { it.source == source && it.kind == SleepRecordKind.EVENTS }} event records · ${state.records.count { it.source == source && it.kind == SleepRecordKind.SUMMARIES }} summary records"
                        )
                    }
                }
            }
        } else
            Text(
                "Sync after a night of use to check for saved records. Recording must already be enabled in Soundcore; this preview does not change that setting."
            )
        TextButton(onClick = { details = !details }) {
            Text(if (details) "Hide sync details" else "Sync details")
        }
        if (details) {
            Text(
                "Original A6611 · firmware 01.57. Reads only; no clock changes, recording toggle or automatic background syncing. Up to 3,000 unique records are retained per pair.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.sources.isNotEmpty())
                Text(
                    "Sources received: ${state.sources}",
                    style = MaterialTheme.typography.bodySmall,
                )
            Text(
                "Exports contain personal sleep records. Review them before sharing.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { confirmDelete = true },
                enabled = state.syncedAt > 0 && !state.loading && !busy,
            ) {
                Text("Delete local sleep history")
            }
            OutlinedButton(
                onClick = { onExport(controller.export()) },
                enabled = (state.syncedAt > 0 || state.attempted) && !state.loading,
            ) {
                Text("Export sleep report")
            }
        }
    }
}
