// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.repair

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import io.github.bytenomad47.a20.device.CompanionRepository
import io.github.bytenomad47.a20.protocol.DeviceSnapshot
import io.github.bytenomad47.a20.protocol.Ear
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal enum class RepairResult {
    NONE,
    CHECKED,
    UNAVAILABLE,
    ACKNOWLEDGED,
    UNKNOWN,
}

internal data class RepairState(
    val ear: String = "L",
    val busy: Boolean = false,
    val stage: String = "",
    val result: RepairResult = RepairResult.NONE,
    val title: String = "Reset charging records",
    val explanation: String =
        "If charging stops early, a reset may help. It does not repair a worn battery.",
    val details: String = "",
    val report: File? = null,
    val lastAttempt: ManualAttempt? = null,
)

/** A fresh UI consent is pair/ear/history bound. It performs no Bluetooth operations. */
internal data class ResetReview(
    val token: String,
    val address: String,
    val ear: String,
    val at: Long,
    val previous: ManualAttempt?,
)

internal class RepairController(
    private val context: Context,
    private val repository: CompanionRepository,
    internal val store: RepairStore = RepairStore(context),
    private val connectionFactory: (String) -> DiagnosticConnection = { address ->
        val adapter =
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: error("Bluetooth is unavailable.")
        SppDiagnosticConnection(adapter, address)
    },
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val operationTimeoutMs: Long = 40_000,
) {
    private val mutable = MutableStateFlow(RepairState(report = store.latest()))
    val state = mutable.asStateFlow()
    private var review: ResetReview? = null

    private data class Request(
        val ear: String,
        val reset: Boolean,
        val address: String,
        val review: ResetReview? = null,
        val acknowledgeUnknown: Boolean = false,
    )

    private var pending: Request? = null
    @Volatile private var connection: DiagnosticConnection? = null

    suspend fun restoreLocalOutcome() {
        repository.awaitInitialized()
        if (!state.value.busy) refreshLast()
    }

    private fun refreshLast() {
        val address = repository.settings.value.address ?: return
        mutable.value = state.value.copy(lastAttempt = store.lastManual(address, state.value.ear))
    }

    fun select(ear: String) {
        require(ear == "L" || ear == "R")
        if (state.value.busy) return
        review = null
        mutable.value = RepairState(ear = ear, report = state.value.report)
        refreshLast()
    }

    fun reviewReset(): ResetReview? {
        if (state.value.busy || pending != null) return null
        val address = repository.settings.value.address ?: return null
        refreshLast()
        return ResetReview(
                UUID.randomUUID().toString(),
                address,
                state.value.ear,
                now(),
                store.lastManual(address, state.value.ear),
            )
            .also { review = it }
    }

    fun dismissReview() {
        review = null
    }

    private fun valid(r: ResetReview): Boolean =
        r.address == repository.settings.value.address &&
            r.ear == state.value.ear &&
            now() - r.at in 0 until 600_000L &&
            store.lastManual(r.address, r.ear) == r.previous

    /** Only a fresh UI token stages a reset. Intents never contain a reset operation. */
    fun enqueue(
        reset: Boolean,
        token: String? = null,
        acknowledgeUnknown: Boolean = false,
    ): Boolean {
        if (state.value.busy || pending != null) return false
        val address = repository.settings.value.address ?: return false
        val consent =
            if (reset) review?.takeIf { it.token == token && valid(it) } ?: return false else null
        if (consent?.previous?.outcome == "unknown" && !acknowledgeUnknown) return false
        pending = Request(state.value.ear, reset, address, consent, acknowledgeUnknown)
        review = null // A queued confirmation can never be replayed.
        mutable.value = state.value.copy(busy = true, stage = "Connecting to earbud…")
        return true
    }

    fun startFailed() {
        pending = null
        review = null
        mutable.value =
            state.value.copy(
                busy = false,
                title = "Could not start",
                explanation = "Grant Nearby devices access and try again.",
            )
    }

    fun cancel() {
        connection?.close()
        if (pending != null) startFailed()
    }

    suspend fun consume() {
        val request = pending ?: return
        pending = null
        var claimed = false
        val id = UUID.randomUUID().toString()
        val report =
            JSONObject()
                .put("schema", 2)
                .put("model", "A6611")
                .put("ear", request.ear)
                .put("operation", if (request.reset) "manual-reset" else "diagnose")
                .put("startedAtPhoneMs", System.currentTimeMillis())
        var reportFile: File? = null
        fun save() {
            reportFile = store.save(id, report)
        }
        val sink = ChargingFaultReset.Sink { update ->
            update.keys().forEach { key -> report.put(key, update.get(key)) }
            mutable.value =
                state.value.copy(
                    stage =
                        when (update.optString("readStage")) {
                            "cached-voltage4" -> "Checking earbud…"
                            "compact-main7" -> "Reading fault count…"
                            "timing2" -> "Checking charging records…"
                            else ->
                                if (update.optBoolean("clearAcknowledged")) "Reset accepted…"
                                else "Resetting charging records…"
                        }
                )
            save()
        }
        try {
            withTimeout(operationTimeoutMs) {
                repository.withRepairLease { address, normal ->
                    check(address == request.address) {
                        "Selected device changed. Open a new confirmation."
                    }
                    checkIdentity(normal, request.ear)
                    if (request.reset)
                        check(request.review?.let(::valid) == true) {
                            "Confirmation expired or device changed. Open a new confirmation."
                        }
                    report.put("activeFirmware", "01.57").put("singleEarVerified", true)
                    val link = connectionFactory(address)
                    connection = link
                    val watchdog =
                        launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            try {
                                delay(35_000)
                            } finally {
                                link.close()
                            }
                        }
                    try {
                        runInterruptible(Dispatchers.IO) {
                            link.connect()
                            if (request.reset) {
                                val consent = request.review!!
                                report
                                    .put("requestId", consent.token)
                                    .put("previousOutcome", consent.previous?.outcome ?: "none")
                                    .put("previousUnknownAcknowledged", request.acknowledgeUnknown)
                                    .put(
                                        "previousAttemptAt",
                                        consent.previous?.at ?: JSONObject.NULL,
                                    )
                                save()
                                ChargingDirectReset.run(
                                    link,
                                    request.ear,
                                    { ear ->
                                        check(ear == request.ear && valid(consent)) {
                                            "Confirmation expired or device changed. Open a new confirmation."
                                        }
                                        save()
                                        store.claimManual(
                                            address,
                                            ear,
                                            consent.token,
                                            consent.previous,
                                        )
                                        claimed = true
                                    },
                                    sink,
                                )
                                store.finishManual(
                                    address,
                                    request.ear,
                                    consent.token,
                                    "acknowledged",
                                )
                                report.put("result", "acknowledged-count-unverified")
                                save()
                                mutable.value =
                                    state.value.copy(
                                        result = RepairResult.ACKNOWLEDGED,
                                        title = "Reset accepted",
                                        explanation =
                                            "The earbud acknowledged the reset. Charge it normally and check whether charging improves. This does not verify the main count or repair battery damage.",
                                    )
                            } else {
                                val snapshot = ChargingFaultReset.read(link, true, sink)
                                check(snapshot.ear == request.ear) {
                                    "Connect only the selected earbud. Leave the other in the closed case."
                                }
                                val count =
                                    snapshot.data
                                        .getJSONObject("main")
                                        .getInt("mainVoltageLimitFaultCounter")
                                report
                                    .put("snapshot", snapshot.data)
                                    .put("result", "count-read")
                                    .put("mainVoltageLimitFaultCounter", count)
                                save()
                                mutable.value =
                                    state.value.copy(
                                        result = RepairResult.CHECKED,
                                        title = "Fault count: $count",
                                        explanation =
                                            if (count <= 2)
                                                "Below the examined firmware's charging-restriction threshold. A nonzero count alone does not establish a battery problem."
                                            else
                                                "Above the examined firmware's charging-restriction threshold. The count does not establish why the events occurred.",
                                    )
                            }
                        }
                    } finally {
                        watchdog.cancel()
                        link.close()
                        connection = null
                    }
                }
            }
        } catch (failure: Exception) {
            val acknowledged = request.reset && report.optBoolean("clearAcknowledged")
            if (acknowledged)
                runCatching {
                    store.finishManual(
                        request.address,
                        request.ear,
                        request.review!!.token,
                        "acknowledged",
                    )
                }
            report
                .put(
                    "result",
                    if (acknowledged) "acknowledged-count-unverified"
                    else if (claimed) "outcome-unknown" else "unavailable",
                )
                .put("error", failure.javaClass.simpleName)
                .put("reason", safeFailure(failure, request.reset))
            runCatching { withContext(NonCancellable + Dispatchers.IO) { save() } }
            mutable.value =
                state.value.copy(
                    result =
                        if (acknowledged) RepairResult.ACKNOWLEDGED
                        else if (claimed) RepairResult.UNKNOWN else RepairResult.UNAVAILABLE,
                    title =
                        if (acknowledged) "Reset accepted · check incomplete"
                        else if (claimed) "Reset outcome unknown"
                        else if (request.reset) "Reset not sent" else "Count unavailable",
                    explanation =
                        if (acknowledged)
                            "The reset was acknowledged, but the follow-up check could not finish. The main count remains unverified. The app will not send it again automatically."
                        else if (claimed)
                            "The request may already have cleared the records, but no complete acknowledgement was received. It will not be retried automatically. Review this uncertainty before deciding on another manual attempt."
                        else safeFailure(failure, request.reset),
                )
        } finally {
            connection?.close()
            connection = null
            mutable.value =
                state.value.copy(
                    busy = false,
                    stage = "",
                    report = reportFile ?: state.value.report,
                    lastAttempt = store.lastManual(request.address, request.ear),
                    details = summarize(report),
                )
        }
    }

    private fun summarize(report: JSONObject): String = buildString {
        appendLine("Model: A6611 · Ear: ${report.optString("ear")}")
        appendLine(
            "Operation: ${report.optString("operation")} · Result: ${report.optString("result","incomplete")}"
        )
        if (report.optString("operation") == "manual-reset") {
            appendLine("Acknowledged: ${report.optBoolean("clearAcknowledged")}")
            appendLine("Main count: not read; no power-loss persistence or charging-fix claim")
        }
        report.optJSONObject("lastMain")?.let {
            appendLine("Main fault count: ${it.getInt("mainVoltageLimitFaultCounter")}")
        }
        if (report.has("reason")) appendLine(report.optString("reason"))
        append("Full numeric diagnostics stay in the local report until you export them.")
    }

    private fun safeFailure(failure: Exception, reset: Boolean): String {
        val allowed =
            setOf(
                "Connect only the selected earbud. Leave the other in the closed case.",
                "This firmware version has not been qualified for charging diagnosis.",
                "Selected device changed. Open a new confirmation.",
                "Confirmation expired or device changed. Open a new confirmation.",
                "Voltage reading outside tested reset range",
                "Timing record outside tested reset profile",
                "Another A20 action is running. Try again when it finishes.",
            )
        return failure.message?.takeIf { it in allowed }
            ?: if (reset)
                "The earbud could not be checked or the request could not be saved. No reset was sent."
            else
                "Reading the main count requires our diagnostic firmware. Official firmware does not expose it here. No reset was sent."
    }

    internal fun checkIdentity(normal: DeviceSnapshot, ear: String) {
        check(
            normal.primary == (if (ear == "L") Ear.LEFT else Ear.RIGHT) &&
                normal.peerLinked == false
        ) {
            "Connect only the selected earbud. Leave the other in the closed case."
        }
        check((if (ear == "L") normal.leftFirmware else normal.rightFirmware) == "01.57") {
            "This firmware version has not been qualified for charging diagnosis."
        }
    }
}
