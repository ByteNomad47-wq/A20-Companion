// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package io.github.bytenomad47.a20.find

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bytenomad47.a20.FindEarbudsPage
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FindUiTest {
    @get:Rule val ui = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun connected(mode: Mode = Mode.BLUETOOTH) =
        CompanionState(selectedName = "A20")
            .withSnapshot(
                DeviceSnapshot(Ear.LEFT, true, 80, 90, 100, mode, "01.57", "01.57"),
                System.currentTimeMillis(),
            )

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun cleanup() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun session(g: FindSessionTest.Gateway) =
        FindSession(g, FindSessionTest.Receipt(), scope)

    private fun playLeft() = ui.onNodeWithContentDescription("Play left sound").performClick()

    private fun stopLeft() = ui.onNodeWithContentDescription("Stop left sound").performClick()

    @Test
    fun missingControllerCannotPlay() {
        ui.setContent { MaterialExpressiveTheme { FindEarbudsPage(connected(), {}) } }
        ui.onNodeWithContentDescription("Play left sound").assertIsNotEnabled()
        ui.onNodeWithText("Play both").assertIsNotEnabled()
    }

    @Test
    fun confirmationHasNoCheckboxAndRepeatOnlySkipsForConfirmedEar() {
        val g = FindSessionTest.Gateway()
        val s = session(g)
        ui.setContent { MaterialExpressiveTheme { FindEarbudsPage(connected(), {}, s) } }
        playLeft()
        ui.onAllNodes(isToggleable()).assertCountEquals(0)
        ui.onNodeWithText("Play sound").assertIsEnabled()
        ui.onNodeWithText("Cancel").performClick()
        assertEquals(0, g.starts)
        playLeft()
        ui.onNodeWithText("Play sound").performClick()
        stopLeft()
        playLeft()
        ui.onNodeWithText("Play a loud sound?").assertDoesNotExist()
        stopLeft()
        ui.onNodeWithContentDescription("Play right sound").performClick()
        ui.onNodeWithText("Play a loud sound?").assertExists()
        ui.onNodeWithText("Cancel").performClick()
        ui.runOnIdle {
            assertEquals(2, g.starts)
            assertEquals(2, g.stops)
        }
    }

    @Test
    fun bothConfirmsBothEarsButLeavingPageClearsConsent() {
        val g = FindSessionTest.Gateway()
        val s = session(g)
        var visible by mutableStateOf(true)
        ui.setContent {
            MaterialExpressiveTheme { if (visible) FindEarbudsPage(connected(), {}, s) }
        }
        ui.onNodeWithText("Play both").performClick()
        ui.onNodeWithText("Play sound").performClick()
        ui.onNodeWithContentDescription("Stop right sound").performClick()
        playLeft()
        ui.onNodeWithText("Play a loud sound?").assertDoesNotExist()
        ui.runOnIdle { visible = false }
        ui.runOnIdle {
            assertEquals(2, g.stops)
            visible = true
        }
        playLeft()
        ui.onNodeWithText("Play a loud sound?").assertExists()
        ui.onNodeWithText("Cancel").performClick()
    }

    @Test
    fun backgroundStopsAndClearsConsentBeforeReturn() {
        val owner =
            object : LifecycleOwner {
                override val lifecycle =
                    LifecycleRegistry.createUnsafe(this).apply {
                        currentState = Lifecycle.State.RESUMED
                    }
            }
        val g = FindSessionTest.Gateway()
        val s = session(g)
        ui.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialExpressiveTheme { FindEarbudsPage(connected(), {}, s) }
            }
        }
        playLeft()
        ui.onNodeWithText("Play sound").performClick()
        ui.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        ui.runOnIdle {
            assertEquals(1, g.stops)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        playLeft()
        ui.onNodeWithText("Play a loud sound?").assertExists()
        ui.onNodeWithText("Cancel").performClick()
        ui.runOnIdle { assertEquals(1, g.starts) }
    }

    @Test
    fun standbyInSleepCanReplayWithoutReturningHome() {
        val g = FindSessionTest.Gateway()
        val s = session(g)
        val state = connected(Mode.SLEEP).copy(connection = Connection.STANDBY)
        ui.setContent { MaterialExpressiveTheme { FindEarbudsPage(state, {}, s) } }
        playLeft()
        ui.onNodeWithText("Play sound").performClick()
        stopLeft()
        playLeft()
        ui.onNodeWithText("Play a loud sound?").assertDoesNotExist()
        stopLeft()
        ui.runOnIdle { assertEquals(2, g.starts) }
    }

    @Test
    fun missingEarIsDisabled() {
        val state = connected().let { it.copy(snapshot = it.snapshot!!.copy(peerLinked = false)) }
        val s = session(FindSessionTest.Gateway())
        ui.setContent { MaterialExpressiveTheme { FindEarbudsPage(state, {}, s) } }
        ui.onNodeWithContentDescription("Play left sound").assertIsEnabled()
        ui.onNodeWithContentDescription("Play right sound").assertIsNotEnabled()
        ui.onNodeWithText("Play both").assertIsNotEnabled()
    }

    private fun render(dark: Boolean) {
        val s = session(FindSessionTest.Gateway())
        ui.setContent {
            MaterialExpressiveTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme()
            ) {
                Surface(Modifier.fillMaxSize()) { FindEarbudsPage(connected(), {}, s) }
            }
        }
        ui.onNodeWithText(
                "Works in Bluetooth and Sleep modes. The phone requests a stop after 30 seconds."
            )
            .assertDoesNotExist()
        ui.onNodeWithText("Stop request accepted").assertDoesNotExist()
        val out = File("build/screenshots/find-${if (dark) "dark" else "light"}.png")
        out.parentFile!!.mkdirs()
        out.outputStream().use {
            ui.onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun renderLight() = render(false)

    @Test fun renderDark() = render(true)
}
