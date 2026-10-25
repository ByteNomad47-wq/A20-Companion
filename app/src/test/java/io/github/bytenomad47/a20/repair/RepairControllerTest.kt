// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.bytenomad47.a20.repair

import android.app.Application
import android.content.Context
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RepairControllerTest {
    private lateinit var context: Context
    private lateinit var repo: CompanionRepository
    private lateinit var gateway: Gateway
    private val address = "AA:BB:CC:11:22:33"
    private var clock = 1000L

    internal class Gateway : DeviceGateway {
        var normal =
            DeviceSnapshot(Ear.LEFT, false, 40, null, 100, Mode.BLUETOOTH, "01.57", "01.57")
        var reads = 0

        override suspend fun readState(): DeviceSnapshot {
            reads++
            return normal
        }

        override suspend fun setMode(mode: Mode) {
            error("Repair must not change mode")
        }

        override suspend fun setSleepVolume(level: Int) {
            error("Repair must not change volume")
        }
    }

    internal open class Connection(ear: String = "L", history: String = history(ear)) :
        ChargingFaultResetTest.Link(ear, history), DiagnosticConnection {
        var connected = false
        @Volatile var wasClosed = false

        override fun connect() {
            connected = true
        }

        override fun close() {
            wasClosed = true
            cancel = true
        }
    }

    @Before
    fun setup(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences("repair_ledger_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        File(context.filesDir, "charging_reports").deleteRecursively()
        gateway = Gateway()
        repo = CompanionRepository(context, gateway)
        repo.awaitInitialized()
        repo.select(address, "Private name")
    }

    @After
    fun cleanup() {
        repo.scope.cancel()
        Dispatchers.resetMain()
    }

    private fun controller(
        store: RepairStore = RepairStore(context),
        links: MutableList<Connection> = mutableListOf(),
    ): RepairController =
        RepairController(context, repo, store, { Connection().also { links.add(it) } }, { clock })

    private suspend fun reset(c: RepairController, unknown: Boolean = false) {
        val review = c.reviewReset()!!
        assertTrue(c.enqueue(true, review.token, unknown))
        c.consume()
    }

    @Test
    fun resetNeedsNoCountOrBaselineAndAllowsSeveralFreshManualRequests(): Unit = runBlocking {
        val links = mutableListOf<Connection>()
        val c = controller(links = links)
        c.store.recordPrior(address, 3)
        repeat(3) {
            val r = c.reviewReset()!!
            assertEquals(0, links.size - it)
            assertTrue(c.enqueue(true, r.token))
            assertFalse(c.enqueue(true, r.token))
            c.consume()
            assertEquals(RepairResult.ACKNOWLEDGED, c.state.value.result)
            assertEquals(1, links.last().clearCount)
            assertFalse(links.last().sent.contains('7'.code))
            assertTrue(links.last().wasClosed)
            assertFalse(c.enqueue(true, r.token))
            val report = JSONObject(c.state.value.report!!.readText())
            assertTrue(report.isNull("mainVoltageLimitFaultCounter"))
            assertFalse(report.getBoolean("mainCountReadBeforeReset"))
            assertFalse(report.getBoolean("ramResetVerified"))
            assertFalse(report.getBoolean("persistenceVerified"))
            assertFalse(report.toString().contains(address))
            assertFalse(report.toString().contains("Private name"))
            assertEquals("acknowledged", c.state.value.lastAttempt!!.outcome)
        }
        assertEquals(3, c.store.prior(address))
        assertEquals(3, links.sumOf { it.clearCount })
    }

    @Test
    fun openingReviewCancelCheckAndUnstagedReplayNeverClear(): Unit = runBlocking {
        val links = mutableListOf<Connection>()
        val c = controller(links = links)
        assertFalse(c.enqueue(true))
        val r = c.reviewReset()!!
        assertTrue(links.isEmpty())
        c.dismissReview()
        assertFalse(c.enqueue(true, r.token))
        assertTrue(c.enqueue(false))
        c.consume()
        c.consume()
        assertEquals(1, links.size)
        assertEquals(0, links.single().clearCount)
        assertEquals(RepairResult.CHECKED, c.state.value.result)
        assertEquals(
            3,
            JSONObject(c.state.value.report!!.readText()).getInt("mainVoltageLimitFaultCounter"),
        )
    }

    @Test
    fun unknownAttemptRequiresSeparateAcknowledgementButDoesNotBlockForever(): Unit = runBlocking {
        var lose = true
        val links = mutableListOf<Connection>()
        val c =
            RepairController(
                context,
                repo,
                connectionFactory = {
                    Connection().also {
                        it.loseAck = lose
                        links.add(it)
                    }
                },
            )
        reset(c)
        assertEquals(RepairResult.UNKNOWN, c.state.value.result)
        val fresh =
            RepairController(
                context,
                repo,
                connectionFactory = { Connection().also { links.add(it) } },
            )
        fresh.restoreLocalOutcome()
        assertEquals("unknown", fresh.state.value.lastAttempt!!.outcome)
        val r = fresh.reviewReset()!!
        assertFalse(fresh.enqueue(true, r.token))
        assertTrue(fresh.enqueue(true, r.token, acknowledgeUnknown = true))
        fresh.consume()
        assertEquals(RepairResult.ACKNOWLEDGED, fresh.state.value.result)
        assertEquals(2, links.sumOf { it.clearCount })
    }

    @Test
    fun confirmationExpiresAndCannotFollowAnotherEarOrDevice(): Unit = runBlocking {
        val links = mutableListOf<Connection>()
        val c = controller(links = links)
        val old = c.reviewReset()!!
        clock += 600_001
        assertFalse(c.enqueue(true, old.token))
        val ear = c.reviewReset()!!
        c.select("R")
        assertFalse(c.enqueue(true, ear.token))
        c.select("L")
        val pair = c.reviewReset()!!
        repo.select("11:22:33:44:55:66", "Other")
        assertFalse(c.enqueue(true, pair.token))
        assertTrue(links.isEmpty())
    }

    @Test
    fun historyChangedSinceConfirmationRequiresFreshReview(): Unit = runBlocking {
        val links = mutableListOf<Connection>()
        val c = controller(links = links)
        val r = c.reviewReset()!!
        c.store.claimManual(address, "L", java.util.UUID.randomUUID().toString(), null)
        assertFalse(c.enqueue(true, r.token))
        assertTrue(links.isEmpty())
    }

    @Test
    fun knownPeerWrongEarAndUnsupportedVersionNeverReachReset(): Unit = runBlocking {
        val links = mutableListOf<Connection>()
        val c = controller(links = links)
        for (normal in
            listOf(
                gateway.normal.copy(peerLinked = true),
                gateway.normal.copy(primary = Ear.RIGHT),
                gateway.normal.copy(leftFirmware = "01.58"),
            )) {
            gateway.normal = normal
            reset(c)
            assertEquals(RepairResult.UNAVAILABLE, c.state.value.result)
        }
        assertTrue(links.isEmpty())
    }

    @Test
    fun failedSaveOrClaimNeverSendsClear(): Unit = runBlocking {
        for (failSave in listOf(true, false)) {
            val store =
                object : RepairStore(context) {
                    override fun save(id: String, report: JSONObject): File {
                        if (failSave) error("Disk full")
                        return super.save(id, report)
                    }

                    override fun claimManual(
                        address: String,
                        ear: String,
                        id: String,
                        expected: ManualAttempt?,
                    ) {
                        error("Commit failed")
                    }
                }
            val links = mutableListOf<Connection>()
            val c = controller(store, links)
            reset(c)
            assertEquals(0, links.sumOf { it.clearCount })
            assertNull(store.lastManual(address, "L"))
        }
    }

    @Test
    fun acknowledgedResetWithFailedFollowupIsNotZeroOrAutomaticRetry(): Unit = runBlocking {
        val link =
            object : Connection() {
                override fun send(c: Int) {
                    super.send(c)
                    if (c == '2'.code && didClear) {
                        chunks.clear()
                        chunks.add(
                            ChargingProtocolTest.Chunk(clock, ChargingProtocolTest.b("BROKEN"))
                        )
                    }
                }
            }
        val c = RepairController(context, repo, connectionFactory = { link })
        reset(c)
        assertEquals(RepairResult.ACKNOWLEDGED, c.state.value.result)
        assertTrue(JSONObject(c.state.value.report!!.readText()).getBoolean("clearAcknowledged"))
        assertEquals(1, link.clearCount)
        assertEquals("acknowledged", c.store.lastManual(address, "L")!!.outcome)
        c.consume()
        assertEquals(1, link.clearCount)
    }

    @Test
    fun processLossAfterClaimCannotReplayRequestAndRetainsUncertainty(): Unit = runBlocking {
        val s = RepairStore(context)
        val id = java.util.UUID.randomUUID().toString()
        s.claimManual(address, "L", id, null)
        val c = controller()
        c.restoreLocalOutcome()
        assertEquals("unknown", c.state.value.lastAttempt!!.outcome)
        c.consume()
        assertFalse(c.enqueue(true, id))
        assertTrue(
            runCatching { s.claimManual(address, "L", id, s.lastManual(address, "L")) }.isFailure
        )
        repo.forget()
        repo.select(address, "A20")
        assertEquals(id, RepairStore(context).lastManual(address, "L")!!.id)
    }

    @Test
    fun legacyExperimentAcknowledgementTakesPrecedenceOverOldUnknownClaim(): Unit = runBlocking {
        val s = RepairStore(context)
        s.recordPrior(address, 3)
        s.claimExperiment(address, "L")
        s.experimentAcknowledged(address, "L")
        val c = controller(s)
        c.restoreLocalOutcome()
        assertEquals("acknowledged", c.state.value.lastAttempt!!.outcome)
        reset(c)
        assertEquals(RepairResult.ACKNOWLEDGED, c.state.value.result)
        assertEquals(3, s.prior(address))
        assertTrue(s.experimentSpent(address, "L"))
    }

    @Test
    fun stockCheckIsUnavailableWithoutInventingZero(): Unit = runBlocking {
        val c =
            RepairController(
                context,
                repo,
                connectionFactory = { Connection(history = "EARSIDE L") },
            )
        assertTrue(c.enqueue(false))
        c.consume()
        assertEquals(RepairResult.UNAVAILABLE, c.state.value.result)
        val r = JSONObject(c.state.value.report!!.readText())
        assertFalse(r.has("mainVoltageLimitFaultCounter"))
        assertFalse(r.has("attemptClaimed"))
    }

    @Test
    fun rightCanResetIndependently(): Unit = runBlocking {
        gateway.normal = gateway.normal.copy(primary = Ear.RIGHT)
        val links = mutableListOf<Connection>()
        val c =
            RepairController(
                context,
                repo,
                connectionFactory = { Connection("R").also { links.add(it) } },
            )
        c.select("R")
        reset(c)
        assertEquals("R", JSONObject(c.state.value.report!!.readText()).getString("ear"))
        assertNull(c.store.lastManual(address, "L"))
        assertEquals(1, links.single().clearCount)
    }

    @Test
    fun timeoutClosesConnectEvenWhenItIgnoresInterruption(): Unit = runBlocking {
        val link =
            object : Connection() {
                override fun connect() {
                    while (!wasClosed) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {}
                    }
                    error("Closed")
                }
            }
        val c =
            RepairController(context, repo, connectionFactory = { link }, operationTimeoutMs = 300)
        val began = System.nanoTime()
        reset(c)
        assertTrue(link.wasClosed)
        assertEquals(0, link.clearCount)
        assertFalse(repo.state.value.busy)
        assertTrue((System.nanoTime() - began) / 1_000_000 < 3000)
    }

    companion object {
        fun history(ear: String): String {
            val side = if (ear == "L") "left" else "right"
            return ChargingFaultResetTest.wire(
                JSONObject(
                        File("src/test/resources/repair-fixtures/case-$side-history.json")
                            .readText()
                    )
                    .getJSONArray("reads")
                    .getJSONObject(1)
                    .getJSONObject("result")
            )
        }
    }
}
