// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Xml
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*
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
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.annotation.experimental.LazyApplication.LazyLoad
import org.robolectric.util.ReflectionHelpers
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = A20Application::class)
@LazyApplication(LazyLoad.ON)
@OptIn(ExperimentalCoroutinesApi::class)
class LauncherShortcutTest {
    private lateinit var app: A20Application
    private lateinit var repo: CompanionRepository
    private val gateway =
        object : DeviceGateway {
            var mode = Mode.BLUETOOTH
            val writes = mutableListOf<Mode>()

            override suspend fun readState() =
                DeviceSnapshot(Ear.RIGHT, true, 90, 90, 100, mode, "01.57", "01.57")

            override suspend fun setMode(mode: Mode) {
                writes += mode
                this.mode = mode
            }
        }

    // Use real IO time for DataStore without advancing the action deadline/refresh clock.
    @Before
    fun setup(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = RuntimeEnvironment.getApplication() as A20Application
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        app.repository.scope.cancel()
        repo = CompanionRepository(app, gateway)
        ReflectionHelpers.setField(app, "repository", repo)
        repo.awaitInitialized()
        repo.forget()
        repo.settings.first { it.address == null }
    }

    @After
    fun cleanup() {
        repo.scope.cancel()
        Dispatchers.resetMain()
    }

    private suspend fun selected() {
        repo.select("AA:BB:CC:11:22:33", "Private test name")
    }

    private fun intent(action: String) =
        Intent(app, ModeShortcutActivity::class.java).setAction(action)

    private fun launch(action: String) =
        Robolectric.buildActivity(ModeShortcutActivity::class.java, intent(action))
            .create()
            .start()
            .resume()

    @Test
    fun launcherMetadataContainsExactlyTwoIsolatedNonDashboardEntries() {
        val info =
            app.packageManager.getActivityInfo(
                ComponentName(app, MainActivity::class.java),
                PackageManager.GET_META_DATA,
            )
        val intents = mutableListOf<Intent>()
        info.loadXmlMetaData(app.packageManager, "android.app.shortcuts").use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "intent")
                    intents += Intent.parseIntent(app.resources, parser, Xml.asAttributeSet(parser))
            }
        }
        assertEquals(
            listOf(LauncherShortcuts.BLUETOOTH, LauncherShortcuts.SLEEP),
            intents.map { it.action },
        )
        assertTrue(
            intents.all { it.component == ComponentName(app, ModeShortcutActivity::class.java) }
        )
        val entry =
            app.packageManager.getActivityInfo(
                ComponentName(app, ModeShortcutActivity::class.java),
                0,
            )
        assertTrue(entry.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertTrue(entry.flags and android.content.pm.ActivityInfo.FLAG_NO_HISTORY != 0)
        assertTrue(entry.taskAffinity.isNullOrEmpty())
    }

    @Test
    fun bothShortcutsStartOnlyThePrivateServiceAndFinishWithoutOpeningTheDashboard() = runBlocking {
        selected()
        for ((action, operation) in
            listOf(
                LauncherShortcuts.SLEEP to "sleep",
                LauncherShortcuts.BLUETOOTH to "bluetooth",
            )) {
            val activity = launch(action)
            try {
                assertTrue(activity.get().isFinishing)
                val service = shadowOf(app).nextStartedService
                assertEquals(ComponentName(app, ActionService::class.java), service.component)
                assertEquals(operation, service.getStringExtra("operation"))
                assertNull(shadowOf(app).nextStartedActivity)
                assertFalse(repo.state.value.readyMode)
                assertFalse(repo.state.value.automationEnabled)
            } finally {
                activity.pause().stop().destroy()
            }
        }
        assertTrue(gateway.writes.isEmpty()) // Service, not activity, owns the Bluetooth work.
    }

    @Test
    fun recreationAndRepeatedResumeDoNotReplayARequest() = runBlocking {
        selected()
        val activity = launch(LauncherShortcuts.SLEEP)
        assertEquals("sleep", shadowOf(app).nextStartedService.getStringExtra("operation"))
        val saved = Bundle()
        activity.saveInstanceState(saved).pause().stop().destroy()
        val restored =
            Robolectric.buildActivity(
                    ModeShortcutActivity::class.java,
                    intent(LauncherShortcuts.SLEEP),
                )
                .create(saved)
                .start()
                .resume()
        try {
            restored.pause().resume()
            assertTrue(restored.get().isFinishing)
            assertNull(shadowOf(app).nextStartedService)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally {
            restored.pause().stop().destroy()
        }
    }

    @Test
    fun missingSetupOrPermissionDiscardsTheRequestWithoutOpeningOrDeferring() = runBlocking {
        val initial = launch(LauncherShortcuts.SLEEP)
        initial.pause().stop().destroy()
        assertNull(shadowOf(app).nextStartedService)
        selected()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val denied = launch(LauncherShortcuts.SLEEP)
        denied.pause().stop().destroy()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(gateway.writes.isEmpty())
    }

    @Test
    fun unknownActionAndModeExtraCannotStartAnOperation() = runBlocking {
        selected()
        val activity =
            Robolectric.buildActivity(
                    ModeShortcutActivity::class.java,
                    intent("io.github.bytenomad47.a20.shortcut.RESET")
                        .putExtra("mode", "sleep"),
                )
                .create()
                .start()
                .resume()
        try {
            assertTrue(activity.get().isFinishing)
            assertNull(shadowOf(app).nextStartedService)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun serviceCompletesGuardedReadWriteReadbackAfterShortcutActivityIsDestroyed() = runBlocking {
        selected()
        val activity = launch(LauncherShortcuts.SLEEP)
        val request = shadowOf(app).nextStartedService
        activity.pause().stop().destroy()
        val service = Robolectric.buildService(ActionService::class.java).create().get()
        try {
            service.onStartCommand(request, 0, 1)
            withContext(Dispatchers.Default) {
                withTimeout(5000) {
                    while (
                        repo.state.value.snapshot?.mode != Mode.SLEEP || repo.state.value.busy
                    ) delay(10)
                }
            }
            assertEquals(listOf(Mode.SLEEP), gateway.writes)
            assertTrue(repo.state.value.message.contains("confirmed"))
            assertNull(shadowOf(app).nextStartedActivity)
        } finally {
            service.onDestroy()
        }
    }

    @Test
    fun oldPinnedShortcutForwardsBeforeBuildingTheDashboard() {
        val activity =
            Robolectric.buildActivity(
                    MainActivity::class.java,
                    Intent(app, MainActivity::class.java).setAction(LauncherShortcuts.SLEEP),
                )
                .create()
        try {
            assertTrue(activity.get().isFinishing)
            val forwarded = shadowOf(app).nextStartedActivity
            assertEquals(ComponentName(app, ModeShortcutActivity::class.java), forwarded.component)
            assertEquals(LauncherShortcuts.SLEEP, forwarded.action)
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            activity.destroy()
        }
    }
}
