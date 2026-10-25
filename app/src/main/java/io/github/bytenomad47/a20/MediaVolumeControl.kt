// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20

import android.media.AudioManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Android media volume in Bluetooth; bounded, readback-confirmed local volume in Sleep. */
@Composable
internal fun MediaVolumeControl(state: CompanionState, onAction: (DeviceAction) -> Unit = {}) {
    if (state.snapshot?.mode == Mode.SLEEP) {
        SleepVolumeControl(state, onAction)
        return
    }
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val owner = LocalLifecycleOwner.current
    val minimum = audio?.getStreamMinVolume(AudioManager.STREAM_MUSIC) ?: 0
    val maximum = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
    var mediaPlaying by remember(audio) { mutableStateOf(audio?.isMusicActive) }
    var position by remember {
        mutableFloatStateOf(
            (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: minimum).toFloat()
        )
    }
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val latestDragging by rememberUpdatedState(dragging)
    LaunchedEffect(owner, audio) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                mediaPlaying = audio?.isMusicActive
                if (!latestDragging)
                    position =
                        (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: minimum).toFloat()
                delay(500)
            }
        }
    }
    val enabled =
        state.audio == AudioConnection.CONNECTED &&
            state.snapshot?.mode == Mode.BLUETOOTH &&
            maximum > minimum &&
            audio?.isVolumeFixed == false
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Media volume", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlaybackButton(state, onAction, mediaPlaying)
            Slider(
                value =
                    position.coerceIn(minimum.toFloat(), maximum.coerceAtLeast(minimum).toFloat()),
                onValueChange = { value ->
                    val requested = value.roundToInt().coerceIn(minimum, maximum)
                    if (enabled) {
                        position = value
                        if (requested != audio?.getStreamVolume(AudioManager.STREAM_MUSIC))
                            runCatching {
                                audio?.setStreamVolume(AudioManager.STREAM_MUSIC, requested, 0)
                            }
                    }
                },
                enabled = enabled,
                valueRange = minimum.toFloat()..maximum.coerceAtLeast(minimum + 1).toFloat(),
                steps = 0,
                interactionSource = interaction,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Media volume" },
            )
        }
        Text(
            "Phone media volume · Bluetooth",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SleepVolumeControl(state: CompanionState, onAction: (DeviceAction) -> Unit) {
    val reported = state.snapshot?.sleepVolume
    var level by
        remember(reported, state.stateReceivedAtMs) {
            mutableFloatStateOf(reported?.toFloat() ?: 0f)
        }
    val enabled =
        !state.busy &&
            state.phone == PhoneConnection.CONNECTED &&
            reported != null &&
            state.snapshot?.let(ModeCapability::supportsModeControl) == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sleep sound volume", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlaybackButton(state, onAction, null)
            Slider(
                value = level,
                onValueChange = { level = it },
                onValueChangeFinished = {
                    val target = level.roundToInt().coerceIn(0, 65)
                    if (enabled && target != reported) onAction(DeviceAction.SetSleepVolume(target))
                },
                enabled = enabled,
                valueRange = 0f..65f,
                steps = 0,
                modifier =
                    Modifier.weight(1f).semantics { contentDescription = "Sleep sound volume" },
            )
        }
        Text(
            if (reported == null) "Pull down to read Sleep volume."
            else "Saved sounds on your earbuds",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
