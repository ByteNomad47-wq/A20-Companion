// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.bytenomad47.a20.repair

import android.Manifest
import android.content.Context
import android.content.Intent
import io.github.bytenomad47.a20.*
import io.github.bytenomad47.a20.device.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.annotation.experimental.LazyApplication.LazyLoad
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LazyApplication(LazyLoad.ON)
class RepairServiceTest {
    private lateinit var app: A20Application
    private lateinit var repo: CompanionRepository
    private lateinit var controller: RepairController
    private val links = mutableListOf<RepairControllerTest.Connection>()

    @Before
    fun setup(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = RuntimeEnvironment.getApplication() as A20Application
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        app.repository.scope.cancel()
        app.getSharedPreferences("repair_ledger_v1", Context.MODE_PRIVATE).edit().clear().commit()
        repo = CompanionRepository(app, RepairControllerTest.Gateway())
        repo.awaitInitialized()
        repo.select("AA:BB:CC:11:22:33", "A20")
        ReflectionHelpers.setField(app, "repository", repo)
        controller =
            RepairController(
                app,
                repo,
                connectionFactory = {
                    RepairControllerTest.Connection().also(links::add)
                },
            )
        ReflectionHelpers.setField(app, "repair", controller)
    }

    @After
    fun cleanup() {
        repo.scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun serviceConsumesStagedDiagnosisOnceAndDoesNotInterpretResetExtras(): Unit = runBlocking {
        assertTrue(controller.enqueue(false))
        val service = Robolectric.buildService(RepairService::class.java).create()
        val intent =
            Intent(app, RepairService::class.java).putExtra("reset", true).putExtra("ear", "R")
        assertEquals(
            android.app.Service.START_NOT_STICKY,
            service.get().onStartCommand(intent, 0, 1),
        )
        withContext(Dispatchers.Default) {
            withTimeout(5000) { while (controller.state.value.busy) delay(10) }
        }
        assertEquals(RepairResult.CHECKED, controller.state.value.result)
        service.get().onStartCommand(intent, 0, 2)
        assertEquals(1, links.size)
        assertEquals(0, links.single().clearCount)
        service.destroy()
    }

    @Test
    fun processRecreatedServiceCannotReplayAnIntentOrRestorePreparation(): Unit = runBlocking {
        val service = Robolectric.buildService(RepairService::class.java).create()
        service
            .get()
            .onStartCommand(Intent(app, RepairService::class.java).putExtra("reset", true), 0, 1)
        assertTrue(links.isEmpty())
        assertFalse(controller.enqueue(true))
        service.destroy()
    }

    @Test
    fun activityRecreationDoesNotReplaceControllerOrReissueRepair(): Unit = runBlocking {
        controller.enqueue(false)
        controller.consume()
        assertNotNull(controller.reviewReset())
        val activity =
            Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        activity.configurationChange()
        assertSame(controller, app.repair)
        assertNotNull(controller.reviewReset())
        assertEquals(1, links.size)
        assertEquals(0, links.single().clearCount)
        activity.pause().stop().destroy()
    }

    @Test
    fun rotationDuringCheckKeepsOneSocketAndServiceStopCancelsWithoutClear(): Unit = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val link =
            object : RepairControllerTest.Connection() {
                override fun connect() {
                    entered.countDown()
                    release.await()
                }

                override fun close() {
                    super.close()
                    release.countDown()
                }
            }
        controller = RepairController(app, repo, connectionFactory = { link.also(links::add) })
        ReflectionHelpers.setField(app, "repair", controller)
        controller.enqueue(false)
        val service = Robolectric.buildService(RepairService::class.java).create()
        service.get().onStartCommand(Intent(app, RepairService::class.java), 0, 1)
        assertTrue(
            withContext(Dispatchers.Default) {
                entered.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        )
        assertTrue(repo.state.value.busy)
        val activity =
            Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        activity.configurationChange()
        assertSame(controller, app.repair)
        assertEquals(1, links.size)
        assertTrue(controller.state.value.busy)
        service.destroy()
        withContext(Dispatchers.Default) {
            withTimeout(5000) { while (controller.state.value.busy) delay(10) }
        }
        assertTrue(link.wasClosed)
        assertEquals(0, link.clearCount)
        assertFalse(repo.state.value.busy)
        activity.pause().stop().destroy()
    }

    @Test
    fun stagedConfirmedResetIsConsumedOnceEvenWhenServiceStartsAgain(): Unit = runBlocking {
        val review = controller.reviewReset()!!
        assertTrue(controller.enqueue(true, review.token))
        val service = Robolectric.buildService(RepairService::class.java).create()
        service.get().onStartCommand(Intent(app, RepairService::class.java), 0, 1)
        withContext(Dispatchers.Default) {
            withTimeout(5000) { while (controller.state.value.busy) delay(10) }
        }
        assertEquals(RepairResult.ACKNOWLEDGED, controller.state.value.result)
        service.get().onStartCommand(Intent(app, RepairService::class.java), 0, 2)
        assertEquals(1, links.size)
        assertEquals(1, links.single().clearCount)
        assertFalse(controller.enqueue(true, review.token))
        service.destroy()
    }

    @Test
    fun repairServiceIsPrivateAndAcceptsNoPublicActionFilter() {
        val info =
            app.packageManager.getServiceInfo(
                android.content.ComponentName(app, RepairService::class.java),
                0,
            )
        assertFalse(info.exported)
        assertFalse(controller.enqueue(true))
    }
}
