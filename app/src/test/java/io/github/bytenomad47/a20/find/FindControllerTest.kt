// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.bytenomad47.a20.find

import android.app.Application
import android.content.Context
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FindControllerTest {
    private lateinit var repo: CompanionRepository
    private lateinit var context: Context
    private val writes = mutableListOf<Pair<Boolean, Boolean>>()
    private var normal =
        DeviceSnapshot(Ear.LEFT, true, 90, 80, 100, Mode.BLUETOOTH, "01.57", "01.57")

    @Before
    fun setup(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences("find_session_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        repo =
            CompanionRepository(
                context,
                object : DeviceGateway {
                    override suspend fun readState() = normal

                    override suspend fun setMode(mode: Mode) {
                        error("Must be excluded")
                    }

                    override suspend fun setFind(left: Boolean, right: Boolean) {
                        writes.add(left to right)
                    }
                },
            )
        repo.awaitInitialized()
        repo.select("AA:BB:CC:11:22:33", "A20")
    }

    @After
    fun cleanup() {
        repo.scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun holdNormalActionsUntilGlobalStopAndKeepTargetFlags(): Unit = runBlocking {
        repo.beginFind(true, false)
        assertEquals(listOf(true to false), writes)
        assertEquals(ActionCode.BUSY, repo.execute(DeviceAction.SetMode(Mode.SLEEP)).code)
        assertTrue(
            runCatching { repo.withRepairLease<Unit> { _, _ -> error("must not run") } }.isFailure
        )
        repo.endFind()
        assertEquals(listOf(true to false, false to false), writes)
        assertFalse(repo.findPending)
        assertFalse(repo.state.value.busy)
    }

    @Test
    fun sleepModeUsesSameFindFlagsAndGlobalStopWithoutSwitchingMode(): Unit = runBlocking {
        normal = normal.copy(mode = Mode.SLEEP)
        repo.beginFind(false, true)
        assertEquals(listOf(false to true), writes)
        assertEquals(Mode.SLEEP, repo.state.value.snapshot?.mode)
        repo.endFind()
        // The released control socket must not prevent a fresh manual start.
        assertEquals(PhoneConnection.CONNECTED, repo.state.value.phone)
        repo.beginFind(true, false)
        repo.endFind()
        assertEquals(listOf(false to true, false to false, true to false, false to false), writes)
    }

    @Test
    fun returningHomeDuringFindStopKeepsSleepPlaybackChannel(): Unit = runBlocking {
        normal = normal.copy(mode = Mode.SLEEP)
        repo.beginFind(true, false)
        repo.setPlaybackVisible(
            true
        ) // Home appeared while the find action still owned the channel.
        repo.endFind()
        assertFalse(repo.findPending)
        assertFalse(repo.state.value.busy)
        assertEquals(Connection.CONNECTED, repo.state.value.connection)
        assertEquals(listOf(true to false, false to false), writes)
        repo.setPlaybackVisible(false)
        assertEquals(Connection.STANDBY, repo.state.value.connection)
    }

    @Test
    fun unknownFirmwareOrMissingTargetNeverStarts(): Unit = runBlocking {
        normal = normal.copy(leftFirmware = "01.58")
        assertTrue(runCatching { repo.beginFind(true, false) }.isFailure)
        assertTrue(writes.isEmpty())
        normal = normal.copy(leftFirmware = "01.57", peerLinked = false)
        assertTrue(runCatching { repo.beginFind(false, true) }.isFailure)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun journalBindsRecoveryToOriginalDeviceAndNeverStartsOnConstruction(): Unit = runBlocking {
        val receipt = PersistentFindReceipt(context) { repo.settings.value.address }
        receipt.save(FindTarget.BOTH)
        val session = createFindSession(context, repo)
        assertEquals(FindPhase.UNCERTAIN, session.state.value.phase)
        assertTrue(writes.isEmpty())
        repo.select("11:22:33:44:55:66", "Other")
        assertTrue(runCatching { receipt.checkDevice() }.isFailure)
        assertEquals(FindTarget.BOTH, receipt.pending())
    }

    @Test
    fun touchEventReleasesActionExclusion(): Unit = runBlocking {
        repo.beginFind(true, true)
        repo.finishFindNotification()
        assertFalse(repo.findPending)
        assertFalse(repo.state.value.busy)
        assertEquals(ActionCode.SUCCESS, repo.execute(DeviceAction.Refresh).code)
    }

    @Test
    fun disconnectReleasesRefreshButKeepsOtherWritesBlockedUntilStop(): Unit = runBlocking {
        repo.beginFind(true, false)
        repo.releaseFindOnDisconnect()
        assertFalse(repo.state.value.busy)
        assertTrue(repo.findPending)
        assertEquals(ActionCode.SUCCESS, repo.execute(DeviceAction.Refresh).code)
        assertEquals(ActionCode.BUSY, repo.execute(DeviceAction.SetMode(Mode.SLEEP)).code)
        repo.endFind()
        assertFalse(repo.findPending)
    }

    @Test
    fun stopNotificationDoesNotClearAnUnrelatedActionsBusyFlag(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                repo.withRepairLease { _, _ ->
                    entered.complete(Unit)
                    release.await()
                }
            }
        entered.await()
        assertTrue(repo.state.value.busy)
        repo.finishFindNotification()
        assertTrue(repo.state.value.busy)
        release.complete(Unit)
        job.join()
        assertFalse(repo.state.value.busy)
    }
}
