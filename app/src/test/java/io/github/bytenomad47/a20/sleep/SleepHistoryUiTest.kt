// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.bytenomad47.a20.sleep

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.bytenomad47.a20.SleepHistoryPage
import io.github.bytenomad47.a20.device.CompanionRepository
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SleepHistoryUiTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun emptyPreviewRequiresExplicitSyncAndExplainsUnqualifiedStages() {
        val context = RuntimeEnvironment.getApplication()
        val repo = CompanionRepository(context)
        val controller = SleepHistoryController(context, repo)
        try {
            ui.setContent {
                MaterialExpressiveTheme {
                    Surface(Modifier.fillMaxSize()) {
                        SleepHistoryPage(controller, null, false, {}, {})
                    }
                }
            }
            ui.onNodeWithText("Sleep history").assertExists()
            ui.onNodeWithText("Sync sleep records").assertIsNotEnabled()
            ui.onNodeWithText("Sleep times and stages are still being validated.", substring = true)
                .assertExists()
            ui.onNodeWithText("Choose your A20 on Home first.").assertExists()
            ui.onNodeWithText("Sync details").performScrollTo().performClick()
            ui.onNodeWithText("Export sleep report").performScrollTo().assertIsNotEnabled()
        } finally {
            repo.scope.cancel()
        }
    }
}
