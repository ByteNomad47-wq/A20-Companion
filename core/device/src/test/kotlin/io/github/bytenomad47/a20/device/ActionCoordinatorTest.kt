// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.device

import io.github.bytenomad47.a20.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActionCoordinatorTest {
    private fun state(mode: Mode? = Mode.BLUETOOTH) =
        DeviceSnapshot(Ear.LEFT, true, 70, 80, 90, mode, "01.57", "01.57")

    private class Gateway(var current: DeviceSnapshot) : DeviceGateway {
        var reads = 0
        var writes = 0
        var volumeWrites = 0
        val playbackWrites = mutableListOf<Pair<Int, Boolean>>()
        var block: CompletableDeferred<Unit>? = null
        var lostAck = false
        var noEffect = false
        var failure: Exception? = null
        var rejectMode = false
        var delayedMode: Mode? = null
        var changeAtRead = Int.MAX_VALUE
        var blockAfterWrite = false

        override suspend fun readState(): DeviceSnapshot {
            reads++
            if (reads >= changeAtRead) current = current.copy(mode = delayedMode)
            block?.await()
            failure?.let { throw it }
            return current
        }

        override suspend fun setSleepVolume(level: Int) {
            volumeWrites++
            if (rejectMode) throw ModeRejectedException()
            if (!noEffect) current = current.copy(sleepVolume = level)
            if (lostAck) throw java.io.IOException("ambiguous")
        }

        override suspend fun setSleepPlayback(slot: Int, playing: Boolean) {
            playbackWrites.add(slot to playing)
            if (rejectMode) throw ModeRejectedException()
            if (!noEffect) current = current.copy(sleepSoundSlot = slot, sleepPlaying = playing)
            if (lostAck) throw java.io.IOException("ambiguous")
        }

        override suspend fun setMode(mode: Mode) {
            writes++
            if (rejectMode) throw ModeRejectedException()
            if (!noEffect && changeAtRead == Int.MAX_VALUE) current = current.copy(mode = mode)
            delayedMode = mode
            if (blockAfterWrite) block = CompletableDeferred()
            if (lostAck) throw java.io.IOException("ambiguous")
        }
    }

    @Test
    fun productionGateSendsNoModeTraffic() = runTest {
        val g = Gateway(state())
        val runner = ActionCoordinator(g, modeVerified = false)
        for (action in
            listOf(
                DeviceAction.ToggleMode,
                DeviceAction.SetMode(Mode.SLEEP),
                DeviceAction.SetMode(Mode.BLUETOOTH),
            )) assertEquals(ActionCode.UNVERIFIED, runner.execute(action).code)
        assertEquals(0, g.reads)
        assertEquals(0, g.writes)
        assertEquals(ActionCode.SUCCESS, runner.execute(DeviceAction.Refresh).code)
        assertEquals(1, g.reads)
    }

    @Test
    fun supportedSetReadsBeforeAndAfterOnlyOneWrite() = runTest {
        val g = Gateway(state())
        val r = ActionCoordinator(g, modeVerified = true)
        assertTrue(r.execute(DeviceAction.SetMode(Mode.SLEEP)).successful)
        assertEquals(2, g.reads)
        assertEquals(1, g.writes)
        assertEquals(Mode.SLEEP, g.current.mode)
        assertTrue(r.execute(DeviceAction.SetMode(Mode.SLEEP)).successful)
        assertEquals(3, g.reads)
        assertEquals(1, g.writes)
    }

    @Test
    fun toggleUsesFreshGatewayStateEachTime() = runTest {
        val g = Gateway(state())
        val r = ActionCoordinator(g, true)
        assertTrue(r.execute(DeviceAction.ToggleMode).successful)
        assertEquals(Mode.SLEEP, g.current.mode)
        g.current = state(Mode.BLUETOOTH) // Physical gesture changed state outside our app.
        assertTrue(r.execute(DeviceAction.ToggleMode).successful)
        assertEquals(Mode.SLEEP, g.current.mode)
        assertEquals(2, g.writes)
    }

    @Test
    fun unknownModeBlocksToggle() = runTest {
        val g = Gateway(state(null))
        assertEquals(
            ActionCode.UNSUPPORTED,
            ActionCoordinator(g, true).execute(DeviceAction.ToggleMode).code,
        )
        assertEquals(0, g.writes)
    }

    @Test
    fun lostAcknowledgementIsReadBackNeverRetried() = runTest {
        val g = Gateway(state())
        g.lostAck = true
        assertTrue(ActionCoordinator(g, true).execute(DeviceAction.SetMode(Mode.SLEEP)).successful)
        assertEquals(1, g.writes)
        assertEquals(2, g.reads)
    }

    @Test
    fun modeControlRequiresActiveFirmwareAndAnyLinkedPeerBeforeWriting() = runTest {
        for (bad in
            listOf(
                state().copy(rightFirmware = "02.00"),
                state().copy(peerLinked = null),
                state().copy(primary = null),
                state(null),
            )) {
            val g = Gateway(bad)
            assertEquals(
                ActionCode.UNSUPPORTED,
                ActionCoordinator(g, modeVerified = true)
                    .execute(DeviceAction.SetMode(Mode.SLEEP))
                    .code,
            )
            assertEquals(0, g.writes)
        }
        val g = Gateway(state())
        assertTrue(
            ActionCoordinator(g, modeVerified = true)
                .execute(DeviceAction.SetMode(Mode.SLEEP))
                .successful
        )
        assertEquals(1, g.writes)
    }

    @Test
    fun eitherEarAloneCanSwitchWithoutPeerFirmware() = runTest {
        for (ear in Ear.entries) {
            val g =
                Gateway(
                    state()
                        .copy(
                            primary = ear,
                            peerLinked = false,
                            leftFirmware = if (ear == Ear.LEFT) "01.57" else null,
                            rightFirmware = if (ear == Ear.RIGHT) "01.57" else null,
                        )
                )
            val runner = ActionCoordinator(g)
            assertTrue(runner.execute(DeviceAction.SetMode(Mode.SLEEP)).successful)
            assertTrue(runner.execute(DeviceAction.SetMode(Mode.BLUETOOTH)).successful)
            assertEquals(2, g.writes)
            g.current = g.current.copy(leftFirmware = null, rightFirmware = null)
            assertEquals(ActionCode.UNSUPPORTED, runner.execute(DeviceAction.ToggleMode).code)
            assertEquals(2, g.writes)
        }
    }

    @Test
    fun delayedTransitionPollsStatusWithoutRepeatingTheModeWrite() = runTest {
        val g = Gateway(state())
        g.changeAtRead = 5
        assertTrue(
            ActionCoordinator(g, modeVerified = true)
                .execute(DeviceAction.SetMode(Mode.SLEEP))
                .successful
        )
        assertEquals(5, g.reads)
        assertEquals(1, g.writes)
    }

    @Test
    fun negativeAcknowledgementIsARefusalNotAnAmbiguousSuccess() = runTest {
        val g = Gateway(state())
        g.rejectMode = true
        assertEquals(
            ActionCode.FAILED,
            ActionCoordinator(g, true).execute(DeviceAction.SetMode(Mode.SLEEP)).code,
        )
        assertEquals(1, g.reads)
        assertEquals(1, g.writes)
    }

    @Test
    fun readbackDeadlineDoesNotReplayAnAlreadySentModeChange() = runTest {
        val g = Gateway(state())
        g.blockAfterWrite = true
        val runner = ActionCoordinator(g, modeVerified = true)
        val result = runner.execute(DeviceAction.SetMode(Mode.SLEEP))
        assertEquals(ActionCode.TIMEOUT, result.code)
        assertTrue(result.message.contains("could not be confirmed"))
        assertEquals(1, g.writes)
        g.block = null
        assertTrue(runner.execute(DeviceAction.Refresh).successful)
        assertEquals(Mode.SLEEP, g.current.mode)
        assertEquals(1, g.writes)
    }

    @Test
    fun sleepVolumeRequiresSleepAndConfirmedReadbackWithOnlyOneWrite() = runTest {
        for (level in listOf(0, 20, 65)) {
            val g = Gateway(state(Mode.SLEEP).copy(sleepVolume = 30))
            val runner = ActionCoordinator(g)
            assertTrue(runner.execute(DeviceAction.SetSleepVolume(level)).successful)
            assertEquals(level, g.current.sleepVolume)
            assertTrue(runner.execute(DeviceAction.SetSleepVolume(level)).successful)
            assertEquals(1, g.volumeWrites)
            assertEquals(0, g.writes)
        }
        for (before in
            listOf(
                state().copy(sleepVolume = 30),
                state(Mode.SLEEP),
                state(Mode.SLEEP).copy(leftFirmware = "02.00", sleepVolume = 30),
            )) {
            val g = Gateway(before)
            assertEquals(
                ActionCode.UNSUPPORTED,
                ActionCoordinator(g).execute(DeviceAction.SetSleepVolume(10)).code,
            )
            assertEquals(0, g.volumeWrites)
        }
    }

    @Test
    fun sleepVolumeAcknowledgementIsInsufficientAndNoWriteIsRetried() = runTest {
        val g = Gateway(state(Mode.SLEEP).copy(sleepVolume = 30))
        g.noEffect = true
        val runner = ActionCoordinator(g)
        assertEquals(ActionCode.FAILED, runner.execute(DeviceAction.SetSleepVolume(10)).code)
        assertEquals(1, g.volumeWrites)
        g.noEffect = false
        g.lostAck = true
        assertTrue(runner.execute(DeviceAction.SetSleepVolume(10)).successful)
        assertEquals(2, g.volumeWrites)
        g.rejectMode = true
        assertEquals(ActionCode.FAILED, runner.execute(DeviceAction.SetSleepVolume(20)).code)
        assertEquals(3, g.volumeWrites)
    }

    private fun playback() =
        state(Mode.SLEEP).copy(sleepSoundSlot = 2, sleepPlaying = true, sleepSlots = setOf(0, 2))

    @Test
    fun sleepPlaybackUsesFreshGestureStateAndPreservesSelectedSound() = runTest {
        val g = Gateway(playback())
        val runner = ActionCoordinator(g)
        assertTrue(runner.execute(DeviceAction.ToggleSleepPlayback).successful)
        assertEquals(listOf(2 to false), g.playbackWrites)
        // A gesture or another controller changed playback after the previous UI snapshot.
        g.current = g.current.copy(sleepPlaying = true, sleepSoundSlot = 0)
        assertTrue(runner.execute(DeviceAction.ToggleSleepPlayback).successful)
        assertEquals(listOf(2 to false, 0 to false), g.playbackWrites)
        assertTrue(runner.execute(DeviceAction.ToggleSleepPlayback).successful)
        assertEquals(0 to true, g.playbackWrites.last())
        assertEquals(0, g.writes)
        assertEquals(0, g.volumeWrites)
    }

    @Test
    fun sleepPlaybackRefusesUnknownOrUnavailableStateWithoutWriting() = runTest {
        for (bad in
            listOf(
                playback().copy(mode = Mode.BLUETOOTH),
                playback().copy(leftFirmware = "01.58"),
                playback().copy(sleepPlaying = null),
                playback().copy(sleepSoundSlot = null),
                playback().copy(sleepSlots = emptySet()),
            )) {
            val g = Gateway(bad)
            assertEquals(
                ActionCode.UNSUPPORTED,
                ActionCoordinator(g).execute(DeviceAction.ToggleSleepPlayback).code,
            )
            assertTrue(g.playbackWrites.isEmpty())
        }
    }

    @Test
    fun sleepPlaybackNeedsReadbackAndNeverRetriesAnAmbiguousToggle() = runTest {
        val g = Gateway(playback())
        g.noEffect = true
        val runner = ActionCoordinator(g)
        assertEquals(ActionCode.FAILED, runner.execute(DeviceAction.ToggleSleepPlayback).code)
        assertEquals(1, g.playbackWrites.size)
        g.noEffect = false
        g.lostAck = true
        assertTrue(runner.execute(DeviceAction.ToggleSleepPlayback).successful)
        assertEquals(2, g.playbackWrites.size)
        g.rejectMode = true
        assertEquals(ActionCode.FAILED, runner.execute(DeviceAction.ToggleSleepPlayback).code)
        assertEquals(3, g.playbackWrites.size)
    }

    @Test
    fun noEffectCannotBeReportedSuccessful() = runTest {
        val g = Gateway(state())
        g.noEffect = true
        assertEquals(
            ActionCode.FAILED,
            ActionCoordinator(g, true).execute(DeviceAction.SetMode(Mode.SLEEP)).code,
        )
        assertEquals(1, g.writes)
    }

    @Test
    fun timeoutHasNoDeferredReplayAndReleasesBusyLock() = runTest {
        val g = Gateway(state())
        g.block = CompletableDeferred()
        val r = ActionCoordinator(g, true)
        assertEquals(ActionCode.TIMEOUT, r.execute(DeviceAction.ToggleMode).code)
        assertEquals(0, g.writes)
        g.block = null
        assertTrue(r.execute(DeviceAction.Refresh).successful)
        assertEquals(0, g.writes)
    }

    @Test
    fun concurrentRequestsAreRejectedWithoutQueueing() = runTest {
        val g = Gateway(state())
        val gate = CompletableDeferred<Unit>()
        g.block = gate
        val r = ActionCoordinator(g, true)
        val first = async { r.execute(DeviceAction.Refresh) }
        runCurrent()
        assertEquals(ActionCode.BUSY, r.execute(DeviceAction.ToggleMode).code)
        gate.complete(Unit)
        assertTrue(first.await().successful)
        assertEquals(1, g.reads)
        assertEquals(0, g.writes)
    }

    @Test
    fun cancellationDoesNotBecomeSuccessOrLeaveRunnerLocked() = runTest {
        val g = Gateway(state())
        g.block = CompletableDeferred()
        val r = ActionCoordinator(g, true)
        val job = launch { r.execute(DeviceAction.ToggleMode) }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(0, g.writes)
        g.block = null
        assertTrue(r.execute(DeviceAction.Refresh).successful)
    }

    @Test
    fun mapsKnownFailuresWithoutLeakingExceptionValues() = runTest {
        for ((exception, expected) in
            listOf(
                SecurityException("PRIVATE") to ActionCode.PERMISSION_REQUIRED,
                ProtocolException("PRIVATE") to ActionCode.UNSUPPORTED,
                java.io.IOException("PRIVATE") to ActionCode.UNAVAILABLE,
            )) {
            val g = Gateway(state())
            g.failure = exception
            val result = ActionCoordinator(g).execute(DeviceAction.Refresh)
            assertEquals(expected, result.code)
            assertFalse(result.message.contains("PRIVATE"))
            assertEquals(0, g.writes)
        }
    }

    @Test
    fun cachePreservesUnknownReadingsAndHasIndependentFreshness() {
        val first = CompanionState().withSnapshot(state(), 1000)
        val second =
            first.withSnapshot(
                state().copy(leftBattery = null, rightBattery = 90, caseBattery = null),
                120000,
            )
        assertEquals(first.left, second.left)
        assertEquals(first.case, second.case)
        assertEquals(120000, second.right.receivedAtMs)
        assertFalse(second.batteryIsCurrent(second.left, 120000))
        assertTrue(second.batteryIsCurrent(second.right, 120000))
        assertFalse(
            second.copy(connection = Connection.STANDBY).batteryIsCurrent(second.right, 120000)
        )
        assertFalse(second.batteryIsCurrent(second.right, 100)) // Phone clock moved backwards.
    }
}
