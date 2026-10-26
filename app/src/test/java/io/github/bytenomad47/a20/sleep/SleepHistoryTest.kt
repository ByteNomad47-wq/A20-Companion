// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.sleep

import android.app.Application
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*
import kotlinx.coroutines.*
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
class SleepHistoryTest {
    private class Gateway : DeviceGateway {
        var firmware = "01.57"
        var fail = false
        var pause: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        val requests = mutableListOf<SleepRecordKind>()
        var writes = 0

        override suspend fun readState() =
            DeviceSnapshot(Ear.LEFT, false, 80, null, 90, Mode.SLEEP, firmware, null)

        override suspend fun setMode(mode: Mode) {
            writes++
        }

        override suspend fun readSleepRecords(
            kind: SleepRecordKind,
            expectedSources: Int,
        ): List<SleepSourceRecords> {
            requests += kind
            entered.complete(Unit)
            pause?.await()
            assertEquals(1, expectedSources)
            if (fail && kind == SleepRecordKind.SUMMARIES)
                throw ProtocolException("Incomplete transfer")
            return listOf(
                SleepSourceRecords(0, listOf(SleepRecord(kind, 0, "01".repeat(kind.recordBytes))))
            )
        }
    }

    @Test
    fun syncIsExplicitReadOnlyDeduplicatedPersistentAndBoundToSelectedPair() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        val fake = Gateway()
        val repo = CompanionRepository(context, fake)
        try {
            repo.awaitInitialized()
            repo.select("AA:BB:CC:11:22:33", "A20")
            val controller = SleepHistoryController(context, repo)
            controller.load()
            assertTrue(fake.requests.isEmpty())
            controller.sync()
            assertEquals(2, controller.state.value.records.size)
            assertEquals(0, fake.writes)
            val original = controller.export()
            assertFalse(original.contains("AA:BB"))
            assertFalse(original.contains("A20\""))
            controller.sync()
            assertEquals(2, controller.state.value.records.size)
            val restored = SleepHistoryController(context, repo)
            restored.load()
            assertEquals(controller.state.value.records, restored.state.value.records)
            val savedBeforeFailure = controller.export()
            fake.fail = true
            controller.sync()
            assertTrue(controller.export().contains("Incomplete transfer"))
            val afterFailure = SleepHistoryController(context, repo)
            afterFailure.load()
            assertEquals(savedBeforeFailure, afterFailure.export())
            assertEquals(2, controller.state.value.records.size)
            restored.deleteLocal()
            assertEquals(0, restored.state.value.syncedAt)
            val deleted = SleepHistoryController(context, repo)
            deleted.load()
            assertTrue(deleted.state.value.records.isEmpty())
            fake.fail = false
            fake.firmware = "01.50"
            val calls = fake.requests.size
            controller.sync()
            assertEquals(calls, fake.requests.size)
            repo.select("11:22:33:44:55:66", "Another A20")
            controller.load()
            assertTrue(controller.state.value.records.isEmpty())
            assertEquals(0, controller.state.value.syncedAt)
        } finally {
            repo.scope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cancellationReleasesSharedLockAndNeverRunsOtherDeviceWrites() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        val fake = Gateway().apply { pause = CompletableDeferred() }
        val repo = CompanionRepository(context, fake)
        try {
            repo.awaitInitialized()
            repo.select("AA:BB:CC:11:22:33", "A20")
            val job = launch { repo.withSleepHistory { _, _ -> fail("Cancelled sync completed") } }
            fake.entered.await()
            assertTrue(repo.state.value.busy)
            assertEquals(ActionCode.BUSY, repo.execute(DeviceAction.SetMode(Mode.BLUETOOTH)).code)
            assertEquals(0, fake.writes)
            job.cancelAndJoin()
            assertFalse(repo.state.value.busy)
            assertEquals(ActionCode.SUCCESS, repo.execute(DeviceAction.Refresh).code)
        } finally {
            repo.scope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun durableFormatRejectsUnknownLayoutAndPreservesOpaqueFields() {
        val records = listOf(SleepRecord(SleepRecordKind.SUMMARIES, 1, "ab".repeat(14)))
        val json = SleepHistoryController.encode(1234, records, "SUMMARIES: Right")
        assertEquals(records, SleepHistoryController.decode(json))
        json.put("format", 999)
        try {
            SleepHistoryController.decode(json)
            fail("Unknown version accepted")
        } catch (_: IllegalStateException) {}
    }
}
