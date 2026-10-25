// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.bytenomad47.a20

import android.app.Application
import android.graphics.Bitmap
import android.media.AudioManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DashboardTest {
    @get:Rule val compose = createComposeRule()

    // Match the app's root Surface so dark-mode text and background are both rendered.
    @Composable
    private fun TestSurface(content: @Composable () -> Unit) {
        Surface(Modifier.fillMaxSize(), content = content)
    }

    @Test
    fun missingBatteriesAreUnavailableAndControlsNeedASelectedDevice() {
        var chosen = 0
        compose.setContent {
            MaterialExpressiveTheme {
                TestSurface { Dashboard(CompanionState(), { chosen++ }, {}, {}, {}) }
            }
        }
        compose.onNodeWithText("Read-only preview").assertDoesNotExist()
        for (part in listOf("left", "right", "case")) {
            compose.onNodeWithTag("battery-age-$part").assertTextEquals("—")
        }
        compose.onNodeWithText("Bluetooth").assertIsNotEnabled()
        compose.onNodeWithText("Sleep").assertIsNotEnabled()
        compose.onNodeWithText("Choose your A20").performScrollTo().performClick()
        assertEquals(1, chosen)
    }

    @Test
    fun reportedValuesDoNotInventPeerConnectionAndCanRenderDark() {
        var chosen = 0
        val snapshot = DeviceSnapshot(Ear.LEFT, false, 80, 90, 100, Mode.SLEEP, "01.57", null)
        val state =
            CompanionState(selectedName = "Sleep A20", message = "Status received from A20.")
                .withSnapshot(snapshot, System.currentTimeMillis())
        compose.setContent {
            MaterialExpressiveTheme(colorScheme = darkColorScheme()) {
                TestSurface { Dashboard(state, { chosen++ }, {}, {}, {}) }
            }
        }
        compose.onNodeWithText("80%").assertExists()
        compose.onNodeWithText("90%").assertExists()
        compose.onNodeWithText("100%").assertExists()
        compose.onNodeWithText("Sleep A20 connected").assertExists().performClick()
        assertEquals(1, chosen)
        compose
            .onAllNodes(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Not connected or not reported",
                )
            )
            .assertCountEquals(1)
        compose.onNodeWithText("Sleep").assertIsSelected()
        compose.onNodeWithText("Bluetooth").assertIsNotSelected()
        compose.onNodeWithText("Refresh status").assertDoesNotExist()
        compose.onNodeWithText("Add Quick Settings tile").assertDoesNotExist()
        val out = File("build/screenshots/dashboard-dark-synthetic.png")
        out.parentFile.mkdirs()
        out.outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun compactBatteryAgesHandleFreshReceiptsAndKeepCardHeightsStable() {
        val parts = listOf("left", "right", "case")
        val at = System.currentTimeMillis()
        var state by
            mutableStateOf(
                CompanionState(
                    selectedName = "Sleep A20",
                    left = BatteryReading(90, at),
                    right = BatteryReading(90, at),
                    case = BatteryReading(90, at),
                )
            )
        compose.setContent {
            MaterialExpressiveTheme { TestSurface { Dashboard(state, {}, {}, {}, {}) } }
        }
        val heights = parts.associateWith {
            compose.onNodeWithTag("battery-$it").fetchSemanticsNode().boundsInRoot.height
        }
        for (part in parts) compose.onNodeWithTag("battery-age-$part").assertTextEquals("now")
        // Reproduce a receipt newer than the remembered display tick, without waiting
        // for the 15-second ticker. Fresh readings must not become "unknown".
        compose.runOnIdle {
            state =
                state.copy(
                    left = BatteryReading(90, System.currentTimeMillis() + 1000),
                    right = BatteryReading(90, at - 5 * 60_000),
                    case = BatteryReading(90, at - 3_600_000),
                )
        }
        compose.onNodeWithTag("battery-age-left").assertTextEquals("now")
        compose.onNodeWithTag("battery-age-right").assertTextEquals("5min ago")
        compose.onNodeWithTag("battery-age-case").assertTextEquals("1h ago")
        for (part in parts) assertEquals(
            heights.getValue(part),
            compose.onNodeWithTag("battery-$part").fetchSemanticsNode().boundsInRoot.height,
        )
        compose.runOnIdle {
            state =
                state.copy(
                    left = BatteryReading(90, at - 86_400_000),
                    right = BatteryReading(90, 0),
                    case = BatteryReading(),
                )
        }
        compose.onNodeWithTag("battery-age-left").assertTextEquals("1d ago")
        compose.onNodeWithTag("battery-age-right").assertTextEquals("—")
        compose.onNodeWithTag("battery-age-case").assertTextEquals("—")
        for (part in parts) assertEquals(
            heights.getValue(part),
            compose.onNodeWithTag("battery-$part").fetchSemanticsNode().boundsInRoot.height,
        )
    }

    @Test
    fun pullDownRefreshesSelectedDevice() {
        var refreshes = 0
        compose.setContent {
            MaterialExpressiveTheme {
                TestSurface {
                    Dashboard(
                        CompanionState(selectedName = "Sleep A20"),
                        {},
                        { refreshes++ },
                        {},
                        {},
                    )
                }
            }
        }
        compose.onNodeWithTag("dashboard").performTouchInput { swipeDown(durationMillis = 800) }
        compose.waitForIdle()
        assertEquals(1, refreshes)
    }

    @Test
    fun volumeControlsMediaStreamAndSleepWritesOnlyOnRelease() {
        val audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        var requested: DeviceAction? = null
        var requests = 0
        var sleeping by mutableStateOf(false)
        compose.setContent {
            MaterialExpressiveTheme {
                val state =
                    CompanionState(audio = AudioConnection.CONNECTED)
                        .withSnapshot(
                            DeviceSnapshot(
                                Ear.LEFT,
                                false,
                                90,
                                null,
                                100,
                                if (sleeping) Mode.SLEEP else Mode.BLUETOOTH,
                                "01.57",
                                null,
                                30,
                            ),
                            1000,
                        )
                TestSurface {
                    MediaVolumeControl(state) {
                        requested = it
                        requests++
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Media volume").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it((maximum / 2).toFloat())
        }
        assertEquals(maximum / 2, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        compose.runOnIdle { sleeping = true }
        val sleepSlider =
            compose.onNodeWithContentDescription("Sleep sound volume").assertIsEnabled()
        sleepSlider.performTouchInput {
            down(Offset(width * .46f, centerY))
            moveTo(Offset(width * .85f, centerY), delayMillis = 120)
        }
        compose.waitForIdle()
        assertEquals(0, requests)
        sleepSlider.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, requests)
        assertTrue(requested is DeviceAction.SetSleepVolume)
        assertTrue((requested as DeviceAction.SetSleepVolume).level in 31..65)
    }

    @Test
    fun bluetoothSliderKeepsFractionalThumbPositionWhileDragging() {
        val audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        compose.setContent {
            MaterialExpressiveTheme {
                TestSurface {
                    MediaVolumeControl(
                        CompanionState(audio = AudioConnection.CONNECTED)
                            .withSnapshot(
                                DeviceSnapshot(
                                    Ear.LEFT,
                                    false,
                                    90,
                                    null,
                                    100,
                                    Mode.BLUETOOTH,
                                    "01.57",
                                    null,
                                ),
                                1000,
                            )
                    )
                }
            }
        }
        val slider = compose.onNodeWithContentDescription("Media volume")
        assertEquals(
            0,
            slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps,
        )
        slider.performTouchInput {
            down(Offset(width * .11f, centerY))
            moveTo(Offset(width * .413f, centerY))
        }
        compose.mainClock.advanceTimeBy(600)
        val held =
            slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue(
            "Thumb snapped to an Android volume step",
            kotlin.math.abs(held - held.toInt()) > .01f,
        )
        assertTrue(held in 0f..maximum.toFloat())
        slider.performTouchInput { up() }
    }

    @Test
    fun expressiveDevicePickerConfirmsChoiceBeforeSelection() {
        val device = PairedChoice("AA:BB:CC:11:22:33", "Test paired A20")
        var dialog: DeviceDialog? by mutableStateOf(DeviceDialog.Choose(listOf(device), false))
        var selected: PairedChoice? = null
        compose.setContent {
            MaterialExpressiveTheme {
                DeviceDialogs(
                    dialog,
                    { dialog = null },
                    {},
                    { dialog = DeviceDialog.Confirm(it) },
                    {
                        selected = it
                        dialog = null
                    },
                    {},
                )
            }
        }
        compose.onNodeWithText(device.name).performClick()
        assertEquals(null, selected)
        compose.onNodeWithText("Use device").performClick()
        assertEquals(device, selected)
    }

    @Test
    fun devicePickerHasLocalForgetAndOppositeBottomActionsEvenWithoutPairedDevices() {
        val saved = PairedChoice("AA:BB:CC:11:22:33", "soundcore Sleep A20")
        var dialog: DeviceDialog? by mutableStateOf(DeviceDialog.Choose(emptyList(), true, saved))
        var forgotten = false
        var bluetoothSettings = 0
        compose.setContent {
            MaterialExpressiveTheme(colorScheme = darkColorScheme()) {
                DeviceDialogs(
                    dialog = dialog,
                    onDismiss = { dialog = null },
                    onShowOther = {},
                    onChoose = {},
                    onConfirm = {},
                    onForget = {
                        forgotten = true
                        dialog = null
                    },
                    onRequestForget = { dialog = DeviceDialog.Forget },
                    onBluetoothSettings = { bluetoothSettings++ },
                )
            }
        }
        compose.onNodeWithText("Choose your A20").assertExists()
        val settings =
            compose.onNodeWithText("Bluetooth settings").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        assertTrue(settings.left < cancel.left)
        assertTrue(kotlin.math.abs(settings.center.y - cancel.center.y) < 2f)
        val name =
            compose
                .onNodeWithText(saved.name, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val selected =
            compose
                .onNodeWithText("Selected", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val other =
            compose
                .onNodeWithText("Other paired devices", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val delete =
            compose
                .onNodeWithContentDescription("Forget soundcore Sleep A20 in Companion")
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(kotlin.math.abs(name.left - selected.left) < 2f)
        assertTrue(kotlin.math.abs(name.left - other.left) < 2f)
        assertTrue(delete.left > name.right)
        val out = File("build/screenshots/device-picker.png")
        out.parentFile?.mkdirs()
        out.outputStream().use {
            compose
                .onNode(isDialog())
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Bluetooth settings").performClick()
        assertEquals(1, bluetoothSettings)
        compose
            .onNodeWithContentDescription("Forget soundcore Sleep A20 in Companion")
            .performClick()
        assertEquals(false, forgotten)
        compose.onNodeWithText("Forget this A20?").assertExists()
        compose.onNodeWithText("Forget").performClick()
        assertEquals(true, forgotten)
    }

    @Test
    fun selectedDeviceAllowsExplicitUiModeRequests() {
        var action: DeviceAction? = null
        compose.setContent {
            MaterialExpressiveTheme {
                TestSurface {
                    Dashboard(
                        CompanionState(selectedName = "Sleep A20"),
                        {},
                        {},
                        { action = it },
                        {},
                    )
                }
            }
        }
        compose.onNodeWithText("Sleep").assertIsEnabled().performClick()
        assertEquals(DeviceAction.SetMode(Mode.SLEEP), action)
    }

    @Test
    fun automationOptInIsSeparateFromReadyMode() {
        var enabled = false
        compose.setContent {
            MaterialExpressiveTheme {
                TestSurface {
                    SettingsPage(
                        s = CompanionState(),
                        onReady = {},
                        onAutomation = { enabled = it },
                        onTheme = {},
                        onExport = {},
                        onBatterySettings = {},
                        onRepair = {},
                        onBack = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Settings").assertExists()
        compose.onNodeWithText("Make it yours.").assertDoesNotExist()
        compose.onNodeWithText("YOUR A20").assertDoesNotExist()
        compose.onNodeWithText("Choose paired device").assertDoesNotExist()
        compose.onNodeWithContentDescription("External automation").performScrollTo().performClick()
        assertEquals(true, enabled)
    }

    @Test
    fun screenshotOfActualEmptyDashboard() {
        compose.setContent {
            MaterialExpressiveTheme(colorScheme = lightColorScheme()) {
                TestSurface { Dashboard(CompanionState(), {}, {}, {}, {}) }
            }
        }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/screenshots/dashboard-light.png")
        out.parentFile.mkdirs()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
