// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20

import android.Manifest
import android.appwidget.*
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.service.quicksettings.TileService
import io.github.bytenomad47.a20.automation.AutomationReceiver
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*
import io.github.bytenomad47.a20.protocol.AutomationContract
import io.github.bytenomad47.a20.widgets.ControlsWidget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.annotation.experimental.LazyApplication.LazyLoad
import org.robolectric.shadows.ShadowService
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = A20Application::class,
    shadows = [EntryPointsTest.TileLifecycleShadow::class],
)
@LazyApplication(LazyLoad.ON)
@OptIn(ExperimentalCoroutinesApi::class)
class EntryPointsTest {
    // Robolectric 4.17's ShadowTileService lacks ShadowService inheritance, causing
    // framework Service.onDestroy to fail a shadow cast. This dispatch-only test
    // needs the base service lifecycle, not a SystemUI tile binder.
    @Implements(TileService::class) class TileLifecycleShadow : ShadowService()

    private lateinit var app: A20Application

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = RuntimeEnvironment.getApplication() as A20Application
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    }

    @After
    fun cleanup() {
        app.repository.scope.cancel()
        Dispatchers.resetMain()
    }

    private fun broadcast(action: String) {
        app.sendBroadcast(
            Intent(action).setComponent(ComponentName(app, AutomationReceiver::class.java))
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun automationRequiresOptInAndDispatchesOnlyFixedActions() = runTest {
        app.repository.awaitInitialized()
        broadcast(AutomationContract.SLEEP)
        assertNull(shadowOf(app).nextStartedService)
        app.repository.setAutomation(true)
        app.repository.state.first { it.automationEnabled }
        for ((action, operation) in
            listOf(
                AutomationContract.SLEEP to "sleep",
                AutomationContract.BLUETOOTH to "bluetooth",
                AutomationContract.TOGGLE_MODE to "toggle",
            )) {
            broadcast(action)
            val service = shadowOf(app).nextStartedService
            assertNotNull(service)
            assertEquals(ComponentName(app, ActionService::class.java), service.component)
            assertEquals(operation, service.getStringExtra("operation"))
        }
        broadcast(AutomationContract.PACKAGE + ".action.RESET")
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun widgetCreationAndResizeRenderCacheWithoutQueryingBluetooth() = runTest {
        app.repository.scope.cancel()
        var reads = 0
        val fake =
            object : DeviceGateway {
                override suspend fun readState(): DeviceSnapshot {
                    reads++
                    throw java.io.IOException("Must not query")
                }

                override suspend fun setMode(mode: Mode) {
                    error("Must not write")
                }
            }
        val repo = CompanionRepository(app, fake)
        ReflectionHelpers.setField(app, "repository", repo)
        repo.awaitInitialized()
        repo.select("AA:BB:CC:11:22:33", "Private test name")
        val manager = AppWidgetManager.getInstance(app)
        val widgets = shadowOf(manager)
        widgets.addBoundWidget(
            42,
            AppWidgetProviderInfo().apply {
                provider = ComponentName(app, ControlsWidget::class.java)
                initialLayout = R.layout.widget_controls
            },
        )
        val provider = ControlsWidget()
        provider.onUpdate(app, manager, intArrayOf(42))
        withContext(Dispatchers.Default) {
            withTimeout(5000) { while (widgets.getViewFor(42) == null) delay(10) }
        }
        provider.onAppWidgetOptionsChanged(app, manager, 42, Bundle())
        assertEquals(0, reads)
        assertNull(repo.state.value.snapshot)
        assertFalse(repo.state.value.busy)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun quickSettingsTapDispatchesModeToggle() {
        val tile = Robolectric.buildService(ModeTileService::class.java).create().get()
        try {
            tile.onClick()
            val service = shadowOf(app).nextStartedService
            assertNotNull(service)
            assertEquals("toggle", service.getStringExtra("operation"))
        } finally {
            tile.onDestroy()
        }
    }
}
