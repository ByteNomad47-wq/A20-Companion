// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.bytenomad47.a20

import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*

/** The next-action icon follows reported state; no optimistic playback update. */
@Composable
internal fun PlaybackButton(
    state: CompanionState,
    onAction: (DeviceAction) -> Unit,
    mediaPlaying: Boolean? = null,
) {
    val context = LocalContext.current
    val sleep = state.snapshot?.mode == Mode.SLEEP
    val snapshot = state.snapshot
    val enabled =
        !state.busy &&
            state.phone == PhoneConnection.CONNECTED &&
            if (sleep)
                snapshot != null &&
                    snapshot.sleepPlaying != null &&
                    snapshot.sleepSoundSlot != null &&
                    snapshot.sleepSlots?.contains(snapshot.sleepSoundSlot) == true &&
                    ModeCapability.supportsModeControl(snapshot)
            else snapshot?.mode == Mode.BLUETOOTH && state.audio == AudioConnection.CONNECTED
    val reported = if (sleep) snapshot?.sleepPlaying else mediaPlaying
    val playing = reported == true
    val corner by
        animateDpAsState(
            if (playing) 16.dp else 28.dp,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "playback shape",
        )
    FilledIconButton(
        onClick = {
            if (sleep) onAction(DeviceAction.ToggleSleepPlayback)
            else
                runCatching {
                    val audio =
                        context.getSystemService(AudioManager::class.java)
                            ?: error("Media controls unavailable")
                    val time = SystemClock.uptimeMillis()
                    audio.dispatchMediaKeyEvent(
                        KeyEvent(
                            time,
                            time,
                            KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            0,
                        )
                    )
                    audio.dispatchMediaKeyEvent(
                        KeyEvent(
                            time,
                            time,
                            KeyEvent.ACTION_UP,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            0,
                        )
                    )
                }
                    .onFailure {
                        Toast.makeText(
                                context,
                                "Android could not send play/pause to your media app.",
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
        },
        enabled = enabled && reported != null,
        shape = RoundedCornerShape(corner),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor =
                    if (playing) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondaryContainer,
                contentColor =
                    if (playing) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        modifier =
            Modifier.size(56.dp).testTag("playback-button").semantics {
                val source = if (sleep) "Sleep sounds" else "Bluetooth media"
                contentDescription = "${if (playing) "Pause" else "Play"} $source"
                stateDescription =
                    when (reported) {
                        true -> "Playing"
                        false -> "Paused"
                        null -> "Playback unknown"
                    }
            },
    ) {
        val color = LocalContentColor.current
        Canvas(Modifier.size(24.dp)) {
            if (playing) {
                for (x in listOf(3.dp.toPx(), 15.dp.toPx())) drawRoundRect(
                    color,
                    topLeft = Offset(x, 0f),
                    size = Size(6.dp.toPx(), size.height),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                )
            } else
                drawPath(
                    Path().apply {
                        moveTo(4.dp.toPx(), 1.dp.toPx())
                        lineTo(22.dp.toPx(), 12.dp.toPx())
                        lineTo(4.dp.toPx(), 23.dp.toPx())
                        close()
                    },
                    color,
                )
        }
    }
}

/** Keep the event channel only while Home is visible and the activity is in the foreground. */
@Composable
internal fun PlaybackPresence(home: Boolean, onVisibilityChanged: (Boolean) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onVisibilityChanged)
    DisposableEffect(home, owner) {
        fun sync() =
            callback(home && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ -> sync() }
        owner.lifecycle.addObserver(observer)
        sync()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            callback(false)
        }
    }
}
