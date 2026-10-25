// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20

import android.app.Application
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryTest {
    private class Gateway : DeviceGateway {
        override val playbackEvents =
            MutableSharedFlow<SleepPlaybackStatus>(extraBufferCapacity = 8)
        var reads = 0
        var writes = 0
        val playbackWrites = mutableListOf<Pair<Int, Boolean>>()
        var value = DeviceSnapshot(Ear.RIGHT, true, 80, 90, 100, Mode.BLUETOOTH, "01.57", "01.57")

        override suspend fun readState(): DeviceSnapshot {
            reads++
            return value
        }

        override suspend fun setSleepPlayback(slot: Int, playing: Boolean) {
            playbackWrites.add(slot to playing)
            value = value.copy(sleepSoundSlot = slot, sleepPlaying = playing)
        }

        override suspend fun setMode(mode: Mode) {
            writes++
        }
    }

    @Test
    fun requestUpdatesActualRepositoryBeforeSuccessAndRetainsMissingCache() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake = Gateway()
        val repo = CompanionRepository(RuntimeEnvironment.getApplication(), fake)
        try {
            repo.awaitInitialized()
            repo.select("AA:BB:CC:11:22:33", "Private test name")
            repo.setVisible(true)
            val result = withContext(Dispatchers.Default) { repo.execute(DeviceAction.Refresh) }
            assertEquals(result.toString(), ActionCode.SUCCESS, result.code)
            assertEquals(80, repo.state.value.left.percent)
            assertEquals(100, repo.state.value.case.percent)
            assertEquals(Ear.RIGHT, repo.state.value.snapshot?.primary)
            assertEquals(Connection.CONNECTED, repo.state.value.connection)
            val previous = repo.state.value.left
            fake.value = fake.value.copy(leftBattery = null, rightBattery = 70, caseBattery = null)
            val second = withContext(Dispatchers.Default) { repo.execute(DeviceAction.Refresh) }
            assertEquals(second.toString(), ActionCode.SUCCESS, second.code)
            assertEquals(previous, repo.state.value.left)
            assertEquals(70, repo.state.value.right.percent)
            assertEquals(100, repo.state.value.case.percent)
            val report = repo.sanitizedReport()
            assertFalse(report.contains("AA:BB"))
            assertFalse(report.contains("Private test name"))
            assertEquals(
                ActionCode.FAILED,
                withContext(Dispatchers.Default) { repo.execute(DeviceAction.SetMode(Mode.SLEEP)) }
                    .code,
            )
            assertTrue(fake.reads >= 4)
            assertEquals(1, fake.writes)
        } finally {
            repo.scope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun sleepPlaybackUpdatesRepositoryAndCanBeRepeatedAfterSleepSocketRelease() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake = Gateway()
        fake.value =
            fake.value.copy(
                mode = Mode.SLEEP,
                sleepSoundSlot = 2,
                sleepPlaying = true,
                sleepSlots = setOf(2),
            )
        val repo = CompanionRepository(RuntimeEnvironment.getApplication(), fake)
        try {
            repo.awaitInitialized()
            repo.select("AA:BB:CC:11:22:33", "A20")
            for (expected in listOf(false, true)) {
                val result =
                    withContext(Dispatchers.Default) {
                        repo.execute(DeviceAction.ToggleSleepPlayback)
                    }
                assertEquals(result.toString(), ActionCode.SUCCESS, result.code)
                assertEquals(expected, repo.state.value.snapshot?.sleepPlaying)
                assertEquals(2, repo.state.value.snapshot?.sleepSoundSlot)
                assertEquals(Connection.STANDBY, repo.state.value.connection)
            }
            assertEquals(listOf(2 to false, 2 to true), fake.playbackWrites)
            assertEquals(0, fake.writes)
        } finally {
            repo.scope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun foregroundPlaybackNotificationsKeepBatteryAgesAndReleaseOnLeavingHome() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val fake = Gateway()
        fake.value =
            fake.value.copy(
                mode = Mode.SLEEP,
                sleepSoundSlot = 2,
                sleepPlaying = true,
                sleepSlots = setOf(2),
            )
        val repo = CompanionRepository(RuntimeEnvironment.getApplication(), fake)
        try {
            repo.awaitInitialized()
            repo.select("AA:BB:CC:11:22:33", "A20")
            repo.setVisible(true)
            repo.setPlaybackVisible(true)
            assertTrue(
                withContext(Dispatchers.Default) { repo.execute(DeviceAction.Refresh) }.successful
            )
            assertEquals(Connection.CONNECTED, repo.state.value.connection)
            val batteries =
                listOf(repo.state.value.left, repo.state.value.right, repo.state.value.case)
            val timestamp = repo.state.value.stateReceivedAtMs
            val reads = fake.reads
            yield()
            fake.playbackEvents.emit(SleepPlaybackStatus(2, false))
            yield()
            assertEquals(false, repo.state.value.snapshot?.sleepPlaying)
            assertEquals(
                batteries,
                listOf(repo.state.value.left, repo.state.value.right, repo.state.value.case),
            )
            assertEquals(timestamp, repo.state.value.stateReceivedAtMs)
            assertEquals(reads, fake.reads)
            assertTrue(fake.playbackWrites.isEmpty())
            repo.setPlaybackVisible(false)
            assertEquals(Connection.STANDBY, repo.state.value.connection)
            repo.setPlaybackVisible(true)
            withContext(Dispatchers.Default) {
                withTimeout(4000) {
                    repo.state.first { it.connection == Connection.CONNECTED && !it.busy }
                }
            }
            assertEquals(reads + 1, fake.reads) // Re-entry does one read, never a start.
            repo.setPlaybackVisible(false)
        } finally {
            repo.scope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun eachBatteryValueAndReceiptTimeSurviveRepositoryRestartWithoutARead() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = RuntimeEnvironment.getApplication()
        val fake = Gateway()
        var repo = CompanionRepository(context, fake)
        try {
            repo.select("AA:BB:CC:11:22:33", "Private test name")
            val before = System.currentTimeMillis()
            assertEquals(
                ActionCode.SUCCESS,
                withContext(Dispatchers.Default) { repo.execute(DeviceAction.Refresh) }.code,
            )
            val first = listOf(repo.state.value.left, repo.state.value.right, repo.state.value.case)
            assertTrue(first.all { it.receivedAtMs in before..System.currentTimeMillis() })
            // A partial response may refresh one side without retiming the other side/case.
            fake.value = fake.value.copy(leftBattery = null, rightBattery = 70, caseBattery = null)
            assertEquals(
                ActionCode.SUCCESS,
                withContext(Dispatchers.Default) { repo.execute(DeviceAction.Refresh) }.code,
            )
            val saved = listOf(repo.state.value.left, repo.state.value.right, repo.state.value.case)
            assertEquals(first[0], saved[0])
            assertEquals(first[2], saved[2])
            val reads = fake.reads
            repo.scope.cancel()
            repo = CompanionRepository(context, fake)
            repo.awaitInitialized()
            assertEquals(
                saved,
                listOf(repo.state.value.left, repo.state.value.right, repo.state.value.case),
            )
            assertEquals(reads, fake.reads)
            assertEquals("AA:BB:CC:11:22:33", repo.settings.value.address)
        } finally {
            repo.scope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun recordsBothExplicitModeResultsWithoutIdentifiers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake =
            object : DeviceGateway {
                var mode = Mode.BLUETOOTH
                var writes = 0

                override suspend fun readState() =
                    DeviceSnapshot(Ear.RIGHT, true, 90, 90, 100, mode, "01.57", "01.57")

                override suspend fun setMode(mode: Mode) {
                    writes++
                    this.mode = mode
                }
            }
        val repo =
            CompanionRepository(
                RuntimeEnvironment.getApplication(),
                fake,
                appVersion = "test-mode-build",
            )
        try {
            repo.select("AA:BB:CC:11:22:33", "Private test name")
            repo.setVisible(true)
            for (mode in listOf(Mode.SLEEP, Mode.BLUETOOTH)) {
                val result =
                    withContext(Dispatchers.Default) { repo.execute(DeviceAction.SetMode(mode)) }
                assertEquals(result.toString(), ActionCode.SUCCESS, result.code)
            }
            assertEquals(2, fake.writes)
            assertEquals(PhoneConnection.CONNECTED, repo.state.value.phone)
            repo.setAudio(AudioConnection.DISCONNECTED)
            repo.release()
            assertEquals(PhoneConnection.CONNECTED, repo.state.value.phone)
            repo.disconnected()
            assertEquals(PhoneConnection.DISCONNECTED, repo.state.value.phone)
            val report = repo.sanitizedReport()
            assertTrue(report.contains("test-mode-build"))
            assertTrue(report.contains("request=sleep result=SUCCESS reported=SLEEP"))
            assertTrue(report.contains("request=bluetooth result=SUCCESS reported=BLUETOOTH"))
            assertFalse(report.contains("AA:BB"))
            assertFalse(report.contains("Private test name"))
        } finally {
            repo.scope.cancel()
            Dispatchers.resetMain()
        }
    }
}
