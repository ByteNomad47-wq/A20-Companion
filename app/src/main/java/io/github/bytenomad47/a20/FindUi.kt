// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.find.*
import io.github.bytenomad47.a20.protocol.Ear
import io.github.bytenomad47.a20.protocol.ModeCapability

/** Confirmation is per ear and page visit, never persisted across navigation or backgrounding. */
@Composable
internal fun FindEarbudsPage(
    state: CompanionState,
    onBack: () -> Unit,
    session: FindSession? = null,
) {
    val flow =
        remember(session) {
            session?.state ?: kotlinx.coroutines.flow.MutableStateFlow(FindState())
        }
    val ringing by flow.collectAsState()
    var pending by remember { mutableStateOf<FindTarget?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ringing.note) {
        val note = ringing.note
        if (
            note != null && note !in setOf("Stop request accepted", "Stopped using earbud controls")
        )
            failure = note
    }
    var confirmed by remember(session, state.selectedName) { mutableStateOf(setOf<FindTarget>()) }
    // Standby means the control socket was released, not that the earbuds left the phone.
    // Every start still checks fresh availability and firmware on the live socket.
    val current =
        state.snapshot != null &&
            (state.connection == Connection.CONNECTED || state.phone == PhoneConnection.CONNECTED)
    val left =
        current && (state.snapshot?.primary == Ear.LEFT || state.snapshot?.peerLinked == true)
    val right =
        current && (state.snapshot?.primary == Ear.RIGHT || state.snapshot?.peerLinked == true)
    val supported = state.snapshot?.let(ModeCapability::supportsModeControl) == true
    val idle = ringing.phase == FindPhase.IDLE
    val canStart = session?.qualified == true && supported && !state.busy && idle
    fun ears(target: FindTarget) =
        when (target) {
            FindTarget.BOTH -> setOf(FindTarget.LEFT, FindTarget.RIGHT)
            else -> setOf(target)
        }
    fun available(target: FindTarget) =
        when (target) {
            FindTarget.LEFT -> left
            FindTarget.RIGHT -> right
            FindTarget.BOTH -> left && right
        }
    fun play(target: FindTarget) {
        if (!canStart || !available(target)) return
        if (confirmed.containsAll(ears(target))) session.start(target, true) else pending = target
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(session, lifecycle) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                    confirmed = emptySet()
                    pending = null
                    session?.leave()
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session?.leave()
        }
    }
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ Home") }
        Text("Find earbuds", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Keep the earbuds out of your ears. The finding sound is loud.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(FindTarget.LEFT to left, FindTarget.RIGHT to right).forEach { (ear, connected) ->
                val active = !idle && (ringing.target == ear || ringing.target == FindTarget.BOTH)
                FindEarCard(
                    ear = ear,
                    available = connected,
                    active = active,
                    enabled =
                        if (active) ringing.phase != FindPhase.STOPPING else canStart && connected,
                    onClick = { if (active) session?.stop() else play(ear) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (idle) {
            OutlinedButton(
                onClick = { play(FindTarget.BOTH) },
                enabled = canStart && left && right,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Play both")
            }
        } else {
            Card(
                Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when (ringing.phase) {
                            FindPhase.PLAYING -> "Sound requested · stop in ${ringing.seconds}s"
                            FindPhase.UNCERTAIN -> "Sound may still be playing. Try Stop all."
                            FindPhase.STOPPING -> "Stopping…"
                            else -> "Starting sound…"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(
                        onClick = { session?.stop() },
                        enabled = ringing.phase != FindPhase.STOPPING,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Stop all")
                    }
                }
            }
        }
        if (!current) Text("Connect your A20 and refresh its status on Home.")
    }
    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text("Sound was not started") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("Close") } },
        )
    }
    pending?.let { target ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Play a loud sound?") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        when (target) {
                            FindTarget.LEFT ->
                                "Take the left earbud out of your ear before playing."
                            FindTarget.RIGHT ->
                                "Take the right earbud out of your ear before playing."
                            FindTarget.BOTH -> "Take both earbuds out of your ears before playing."
                        }
                    )
                    Text(
                        "The sound may continue if your phone disconnects. Use Stop all or double/triple tap either bud to cancel; the case only pauses it."
                    )
                    Text(
                        "You can stop and replay the confirmed earbuds without this warning until you leave this page."
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (canStart && available(target)) {
                            confirmed = confirmed + ears(target)
                            pending = null
                            session.start(target, true)
                        }
                    },
                    enabled = canStart && available(target),
                ) {
                    Text("Play sound")
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FindEarCard(
    ear: FindTarget,
    available: Boolean,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (ear == FindTarget.LEFT) "Left" else "Right"
    Card(
        modifier,
        shape = RoundedCornerShape(if (active) 24.dp else 36.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (active) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh
            ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleLarge)
            Image(
                painterResource(R.drawable.sleep_a20_bud),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier.fillMaxWidth().height(116.dp).graphicsLayer {
                        scaleX = if (ear == FindTarget.LEFT) -1f else 1f
                        alpha = if (available || active) 1f else .38f
                    },
            )
            FilledIconButton(
                onClick = onClick,
                enabled = enabled,
                modifier =
                    Modifier.size(64.dp).semantics {
                        contentDescription =
                            "${if (active) "Stop" else "Play"} ${label.lowercase()} sound"
                    },
            ) {
                val color = LocalContentColor.current
                Canvas(Modifier.size(24.dp)) {
                    if (active) drawRoundRect(color, cornerRadius = CornerRadius(3.dp.toPx()))
                    else
                        drawPath(
                            Path().apply {
                                moveTo(size.width * .18f, 0f)
                                lineTo(size.width, size.height * .5f)
                                lineTo(size.width * .18f, size.height)
                                close()
                            },
                            color,
                        )
                }
            }
            Text(
                if (active) "Sound requested" else if (available) "Ready" else "Not connected",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
