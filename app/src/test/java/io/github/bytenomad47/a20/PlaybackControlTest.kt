// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.bytenomad47.a20

import android.app.Application
import android.graphics.Bitmap
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackControlTest {
    @get:Rule val ui = createComposeRule()

    private fun connected(mode: Mode) =
        CompanionState(selectedName = "A20", audio = AudioConnection.CONNECTED)
            .withSnapshot(
                DeviceSnapshot(
                    Ear.LEFT,
                    false,
                    90,
                    80,
                    100,
                    mode,
                    "01.57",
                    null,
                    30,
                    2,
                    true,
                    setOf(2),
                ),
                System.currentTimeMillis(),
            )

    @Test
    fun sleepRoutesThroughNormalActionAndBusyOrUnknownStateDisablesIt() {
        var state by mutableStateOf(connected(Mode.SLEEP))
        val actions = mutableListOf<DeviceAction>()
        var dark by mutableStateOf(true)
        ui.setContent {
            MaterialExpressiveTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme()
            ) {
                Surface(Modifier.fillMaxSize()) {
                    Dashboard(state, {}, {}, { actions.add(it) }, {})
                }
            }
        }
        ui.onNodeWithContentDescription("Pause Sleep sounds").assertIsEnabled().performClick()
        assertEquals(listOf(DeviceAction.ToggleSleepPlayback), actions)
        val button =
            ui.onNodeWithContentDescription("Pause Sleep sounds").fetchSemanticsNode().boundsInRoot
        val slider =
            ui.onNodeWithContentDescription("Sleep sound volume").fetchSemanticsNode().boundsInRoot
        assertTrue(button.right < slider.left)
        assertTrue(kotlin.math.abs(button.center.y - slider.center.y) < 2f)
        ui.onNodeWithText("Play / pause").assertDoesNotExist()
        ui.onNodeWithTag("playback-button")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
        screenshot("playback-playing-dark")
        ui.runOnIdle { state = state.copy(snapshot = state.snapshot!!.copy(sleepPlaying = false)) }
        ui.onNodeWithContentDescription("Play Sleep sounds").assertIsEnabled()
        ui.onNodeWithTag("playback-button")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Paused"))
        assertEquals(button, ui.onNodeWithTag("playback-button").fetchSemanticsNode().boundsInRoot)
        screenshot("playback-paused-dark")
        ui.runOnIdle { dark = false }
        screenshot("playback-paused-light")
        ui.runOnIdle { state = state.copy(snapshot = state.snapshot!!.copy(sleepPlaying = true)) }
        screenshot("playback-playing-light")
        ui.runOnIdle { state = state.copy(busy = true) }
        ui.onNodeWithTag("playback-button").assertIsNotEnabled()
        ui.runOnIdle {
            state = state.copy(busy = false, snapshot = state.snapshot!!.copy(sleepPlaying = null))
        }
        ui.onNodeWithTag("playback-button").assertIsNotEnabled()
    }

    @Test
    fun bluetoothSendsOneBalancedAndroidMediaKeyPairAndNoSleepCommand() {
        var state by mutableStateOf(connected(Mode.BLUETOOTH))
        val actions = mutableListOf<DeviceAction>()
        val audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val shadow = Shadows.shadowOf(audio)
        shadow.clearDispatchedMediaKeyEvents()
        ui.setContent { MaterialExpressiveTheme { MediaVolumeControl(state) { actions.add(it) } } }
        ui.onNodeWithContentDescription("Play Bluetooth media").assertIsEnabled().performClick()
        assertTrue(actions.isEmpty())
        assertEquals(
            listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP),
            shadow.dispatchedMediaKeyEvents.map { it.action },
        )
        assertTrue(
            shadow.dispatchedMediaKeyEvents.all { it.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
        )
        // Sending a media key alone cannot flip the reported icon optimistically.
        ui.onNodeWithContentDescription("Play Bluetooth media").assertExists()
        ui.runOnIdle { shadow.setIsMusicActive(true) }
        ui.mainClock.advanceTimeBy(600)
        ui.onNodeWithContentDescription("Pause Bluetooth media").assertExists()
        ui.runOnIdle { shadow.setIsMusicActive(false) }
        ui.mainClock.advanceTimeBy(600)
        ui.onNodeWithContentDescription("Play Bluetooth media").assertExists()
        ui.runOnIdle { state = state.copy(audio = AudioConnection.DISCONNECTED) }
        ui.onNodeWithContentDescription("Play Bluetooth media").assertIsNotEnabled()
    }

    private fun screenshot(name: String) {
        val image = File("build/screenshots/$name.png")
        image.parentFile!!.mkdirs()
        image.outputStream().use {
            ui.onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun playbackPresenceIsLimitedToHomeAndForegroundLifecycle() {
        val owner =
            object : LifecycleOwner {
                override val lifecycle =
                    LifecycleRegistry.createUnsafe(this).apply {
                        currentState = Lifecycle.State.RESUMED
                    }
            }
        var home by mutableStateOf(true)
        val events = mutableListOf<Boolean>()
        ui.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PlaybackPresence(home) { events.add(it) }
            }
        }
        ui.runOnIdle {
            assertEquals(true, events.last())
            owner.lifecycle.currentState = Lifecycle.State.CREATED
        }
        ui.runOnIdle {
            assertEquals(false, events.last())
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        ui.runOnIdle {
            assertEquals(true, events.last())
            home = false
        }
        ui.runOnIdle { assertEquals(false, events.last()) }
    }
}
