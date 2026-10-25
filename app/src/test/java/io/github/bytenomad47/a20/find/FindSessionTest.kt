// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.find

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FindSessionTest {
    class Receipt : FindReceipt {
        var value: FindTarget? = null

        override fun pending() = value

        override fun save(target: FindTarget?) {
            value = target
        }
    }

    class Gateway : FindGateway {
        override var qualified = true
        var starts = 0
        var stops = 0
        var lost = false

        override suspend fun start(target: FindTarget) {
            starts++
            if (lost) error("lost")
        }

        override suspend fun stop(target: FindTarget) {
            stops++
            if (lost) error("lost")
        }
    }

    @Test
    fun confirmationGateAndThirtySecondStop() = runTest {
        val g = Gateway()
        val r = Receipt()
        val s = FindSession(g, r, this)
        s.start(FindTarget.LEFT, false)
        assertEquals(0, g.starts)
        g.qualified = false
        s.start(FindTarget.LEFT, true)
        assertEquals(0, g.starts)
        g.qualified = true
        s.start(FindTarget.LEFT, true)
        s.start(FindTarget.RIGHT, true)
        runCurrent()
        assertEquals(1, g.starts)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, g.stops)
        assertNull(r.value)
        assertEquals(FindPhase.IDLE, s.state.value.phase)
    }

    @Test
    fun lostStartNeverRetriesAndRecoveryOnlyStops() = runTest {
        val g = Gateway()
        val r = Receipt()
        val s = FindSession(g, r, this)
        g.lost = true
        s.start(FindTarget.BOTH, true)
        runCurrent()
        assertEquals(FindPhase.UNCERTAIN, s.state.value.phase)
        s.start(FindTarget.BOTH, true)
        assertEquals(1, g.starts)
        s.disconnected() // Model loss of the old process/session timer.
        val restored = FindSession(g, r, this)
        g.lost = false
        restored.reconnect()
        runCurrent()
        assertEquals(1, g.starts)
        assertEquals(1, g.stops)
        assertNull(r.value)
    }

    @Test
    fun navigationAndBackgroundStop() = runTest {
        val g = Gateway()
        val s = FindSession(g, Receipt(), this)
        s.start(FindTarget.RIGHT, true)
        runCurrent()
        s.leave()
        runCurrent()
        assertEquals(1, g.stops)
        advanceTimeBy(40_000)
        assertEquals(1, g.stops)
    }

    @Test
    fun lostAcknowledgementStillRequestsStopAtDeadline() = runTest {
        val g = Gateway()
        val s = FindSession(g, Receipt(), this)
        g.lost = true
        s.start(FindTarget.LEFT, true)
        runCurrent()
        assertEquals(FindPhase.UNCERTAIN, s.state.value.phase)
        g.lost = false
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, g.starts)
        assertEquals(1, g.stops)
        assertEquals(FindPhase.IDLE, s.state.value.phase)
    }

    @Test
    fun touchCancellationClearsSessionAndStopsTimer() = runTest {
        val g = Gateway()
        val receipt = Receipt()
        val s = FindSession(g, receipt, this)
        s.start(FindTarget.BOTH, true)
        runCurrent()
        s.deviceStopped()
        runCurrent()
        assertEquals(FindPhase.IDLE, s.state.value.phase)
        assertNull(receipt.value)
        assertEquals("Stopped using earbud controls", s.state.value.note)
        advanceTimeBy(40_000)
        assertEquals(0, g.stops)
    }

    @Test
    fun guaranteedPreflightRefusalDoesNotLeaveAnUncertainReceipt() = runTest {
        val r = Receipt()
        val g =
            object : FindGateway {
                override val qualified = true

                override suspend fun start(target: FindTarget) {
                    throw io.github.bytenomad47.a20.device.FindNotSentException(
                        "No request sent"
                    )
                }

                override suspend fun stop(target: FindTarget) {
                    error("Must not stop an unsent request")
                }
            }
        val session = FindSession(g, r, this)
        session.start(FindTarget.LEFT, true)
        runCurrent()
        assertEquals(FindPhase.IDLE, session.state.value.phase)
        assertNull(r.value)
        advanceTimeBy(40_000)
    }

    @Test
    fun aLateConnectionEventDoesNotCancelANewActiveRequest() = runTest {
        val g = Gateway()
        val s = FindSession(g, Receipt(), this)
        s.start(FindTarget.LEFT, true)
        runCurrent()
        s.reconnect()
        runCurrent()
        assertEquals(0, g.stops)
        s.stop()
        runCurrent()
    }

    @Test
    fun failedReceiptNeverSendsOrCrashes() = runTest {
        val g = Gateway()
        val r =
            object : FindReceipt {
                override fun pending(): FindTarget? = null

                override fun save(target: FindTarget?) {
                    error("Disk full")
                }
            }
        val s = FindSession(g, r, this)
        s.start(FindTarget.LEFT, true)
        runCurrent()
        assertEquals(0, g.starts)
        assertEquals(FindPhase.IDLE, s.state.value.phase)
    }
}
