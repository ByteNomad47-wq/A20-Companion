// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.github.bytenomad47.a20.repair

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.bytenomad47.a20.GuidedRepairPage
import io.github.bytenomad47.a20.device.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RepairUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repo: CompanionRepository
    private lateinit var c: RepairController
    private lateinit var context: Context

    @Before
    fun setup(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences("repair_ledger_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        repo = CompanionRepository(context, RepairControllerTest.Gateway())
        repo.awaitInitialized()
        repo.select("AA:BB:CC:11:22:33", "A20")
        c =
            RepairController(
                context,
                repo,
                connectionFactory = { RepairControllerTest.Connection() },
            )
    }

    @After
    fun cleanup() {
        repo.scope.cancel()
        Dispatchers.resetMain()
    }

    private fun show(reset: (String, Boolean) -> Unit = { _, _ -> }, check: () -> Unit = {}) {
        compose.setContent {
            MaterialExpressiveTheme {
                Surface(Modifier.fillMaxSize()) { GuidedRepairPage(c, {}, reset, check, {}) }
            }
        }
    }

    @Test
    fun normalPageOffersDirectResetAndHidesCheckAndExport() {
        show()
        compose.onNodeWithText("Reset left earbud").assertIsEnabled()
        compose.onNodeWithText("Check left fault count").assertDoesNotExist()
        compose.onNodeWithText("Export latest report").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Check left fault count").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Export latest report").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Hide advanced").performScrollTo().performClick()
        compose.onNodeWithText("Check left fault count").assertDoesNotExist()
    }

    @Test
    fun directConfirmationDoesNotReadCountAndCancelSendsNothing() {
        var sends = 0
        var checks = 0
        show({ _, _ -> sends++ }, { checks++ })
        compose.onNodeWithText("Reset left earbud").performScrollTo().performClick()
        compose.onNodeWithText("Reset left charging records?").assertExists()
        compose.onNodeWithText("Previously reset this pair?").assertDoesNotExist()
        compose.onNodeWithText("Reset left").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, sends)
        assertEquals(0, checks)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun acknowledgedPreviousResetDoesNotBlockAnotherFreshConfirmation(): Unit = runBlocking {
        val address = "AA:BB:CC:11:22:33"
        val id = java.util.UUID.randomUUID().toString()
        c.store.claimManual(address, "L", id, null)
        c.store.finishManual(address, "L", id, "acknowledged")
        var sends = 0
        show({ token, unknown ->
            assertFalse(unknown)
            assertNotEquals(id, token)
            sends++
        })
        compose.onNodeWithText("Reset left earbud").performScrollTo().performClick()
        compose.onAllNodesWithText("Last attempt:", substring = true)[0].assertExists()
        compose.onNodeWithText("Reset left").assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Reset left").assertIsEnabled().performClick()
        assertEquals(1, sends)
    }

    @Test
    fun uncertainPreviousAttemptNeedsBothAcknowledgements(): Unit = runBlocking {
        c.store.claimManual("AA:BB:CC:11:22:33", "L", java.util.UUID.randomUUID().toString(), null)
        var sends = 0
        show({ _, unknown ->
            assertTrue(unknown)
            sends++
        })
        compose.onNodeWithText("Reset left earbud").performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onNodeWithText("Reset left").assertIsNotEnabled()
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        compose.onNodeWithText("Reset left").assertIsEnabled().performClick()
        assertEquals(1, sends)
    }

    @Test
    fun advancedCountCheckDoesNotOpenResetDialog() {
        var checks = 0
        show(check = { checks++ })
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Check left fault count").performScrollTo().performClick()
        assertEquals(1, checks)
        compose.onNodeWithText("Reset left charging records?").assertDoesNotExist()
    }
}
