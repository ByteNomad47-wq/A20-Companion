// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.repair

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.Locale
import org.json.JSONObject

internal data class ManualAttempt(val id: String, val at: Long?, val outcome: String)

/** Separate from normal device preferences; retains legacy history and per-request receipts. */
internal open class RepairStore(context: Context) {
    private val prefs = context.getSharedPreferences("repair_ledger_v1", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "charging_reports").apply { mkdirs() }

    internal fun pair(address: String): String =
        RepairHash.hash(
            "A6611-diagnostic113|${address.uppercase(Locale.ROOT)}".toByteArray(),
            "SHA-256",
        )

    fun prior(address: String): Int? =
        pair(address).let {
            if (prefs.contains("prior_$it")) prefs.getInt("prior_$it", 4) else null
        }

    @Synchronized
    fun recordPrior(address: String, mask: Int) {
        require(mask in 0..4)
        check(prior(address) == null) { "Previous-reset answer is already saved." }
        check(prefs.edit().putInt("prior_${pair(address)}", mask).commit()) {
            "Could not save previous reset history."
        }
    }

    fun spent(address: String, ear: String): Boolean {
        val prior = prior(address) ?: return true
        return prior == 4 ||
            prior and (if (ear == "L") 1 else 2) != 0 ||
            prefs.contains("attempt_${pair(address)}_$ear")
    }

    fun outcome(address: String, ear: String): String? =
        prefs.getString("attempt_${pair(address)}_$ear", null)

    @Synchronized
    open fun claim(address: String, ear: String) {
        require(ear == "L" || ear == "R")
        check(!spent(address, ear)) { "This earbud has no unused reset attempt." }
        check(prefs.edit().putString("attempt_${pair(address)}_$ear", "unknown").commit()) {
            "Could not save the one-use attempt. No reset sent."
        }
    }

    fun verified(address: String, ear: String) {
        check(prefs.edit().putString("attempt_${pair(address)}_$ear", "cleared").commit())
    }

    /** finishWrite flushes and fsyncs before rename; a failed write cannot enable a reset. */
    open fun save(id: String, report: JSONObject): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        check(report.toString(2).toByteArray().size <= 512 * 1024) { "Report limit exceeded." }
        val target = File(directory, "$id.json")
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(report.toString(2).toByteArray())
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
        check(target.readBytes().contentEquals(report.toString(2).toByteArray())) {
            "Report could not be durably saved."
        }
        directory
            .listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(40)
            ?.forEach { it.delete() }
        return target
    }

    /** Separate one-extra-attempt experiment; never erase or release normal repair claims. */
    fun experimentSpent(address: String, ear: String): Boolean =
        prefs.contains("experiment_v1_${pair(address)}_$ear")

    @Synchronized
    open fun claimExperiment(address: String, ear: String) {
        require(ear == "L" || ear == "R")
        check(!experimentSpent(address, ear)) { "Experiment already attempted. Do not resend." }
        val editor = prefs.edit().putString("experiment_v1_${pair(address)}_$ear", "unknown")
        if (!prefs.contains("attempt_${pair(address)}_$ear"))
            editor.putString("attempt_${pair(address)}_$ear", "unknown")
        check(editor.commit()) { "Could not save experiment claim. No reset sent." }
    }

    fun experimentAcknowledged(address: String, ear: String) {
        check(
            prefs
                .edit()
                .putString("experiment_v1_${pair(address)}_$ear", "acknowledged-count-unverified")
                .commit()
        )
    }

    fun saveExperimentBaseline(
        address: String,
        ear: String,
        snapshot: ChargingFaultReset.Snapshot,
    ) {
        check(snapshot.ear == ear)
        ChargingResetExperiment.guard(snapshot)
        val data =
            JSONObject()
                .put("ear", ear)
                .put("savedAt", System.currentTimeMillis())
                .put("hash", snapshot.hash)
                .put("timingHash", snapshot.timingHash)
                .put("snapshot", snapshot.data)
        check(
            prefs
                .edit()
                .putString("experiment_baseline_${pair(address)}_$ear", data.toString())
                .commit()
        )
    }

    fun experimentBaseline(address: String, ear: String): JSONObject? = runCatching {
        val text = prefs.getString("experiment_baseline_${pair(address)}_$ear", null) ?: return null
        val data = JSONObject(text)
        check(data.getString("ear") == ear)
        check(System.currentTimeMillis() - data.getLong("savedAt") in 0 until 86_400_000L)
        val snap =
            ChargingFaultReset.Snapshot(
                ear,
                data.getString("hash"),
                data.getString("timingHash"),
                data.getJSONObject("snapshot"),
            )
        ChargingResetExperiment.guard(snap)
        data
    }
        .getOrNull()

    /** Old lifetime claims remain historical records; they no longer bar a fresh manual request. */
    fun lastManual(address: String, ear: String): ManualAttempt? {
        require(ear == "L" || ear == "R")
        val text = prefs.getString("manual_last_${pair(address)}_$ear", null)
        if (text != null)
            return runCatching {
                val v = JSONObject(text)
                ManualAttempt(v.getString("id"), v.getLong("at"), v.getString("outcome"))
            }
                .getOrElse { ManualAttempt("corrupt-history", null, "unknown") }
        val experiment = prefs.getString("experiment_v1_${pair(address)}_$ear", null)
        if (experiment != null)
            return ManualAttempt(
                "legacy-experiment-$experiment",
                null,
                if (experiment.startsWith("acknowledged")) "acknowledged" else "unknown",
            )
        val previous = outcome(address, ear)
        if (previous != null)
            return ManualAttempt(
                "legacy-normal-$previous",
                null,
                if (previous == "cleared") "verified-zero" else "unknown",
            )
        val prior = prior(address)
        if (prior == 4) return ManualAttempt("legacy-unsure", null, "unknown")
        if (prior != null && prior and (if (ear == "L") 1 else 2) != 0)
            return ManualAttempt("legacy-reported", null, "recorded")
        return null
    }

    @Synchronized
    open fun claimManual(address: String, ear: String, id: String, expected: ManualAttempt?) {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        check(lastManual(address, ear) == expected) { "Attempt history changed." }
        check(!prefs.contains("manual_request_$id")) { "Request already used." }
        val record =
            JSONObject()
                .put("id", id)
                .put("at", System.currentTimeMillis())
                .put("outcome", "unknown")
        check(
            prefs
                .edit()
                .putBoolean("manual_request_$id", true)
                .putString("manual_last_${pair(address)}_$ear", record.toString())
                .commit()
        ) {
            "Could not save manual attempt. No reset sent."
        }
    }

    @Synchronized
    open fun finishManual(address: String, ear: String, id: String, outcome: String) {
        require(outcome == "acknowledged")
        val last = lastManual(address, ear) ?: error("Missing manual attempt")
        check(last.id == id)
        val record = JSONObject().put("id", id).put("at", last.at).put("outcome", outcome)
        check(
            prefs.edit().putString("manual_last_${pair(address)}_$ear", record.toString()).commit()
        )
    }

    fun latest(): File? =
        directory.listFiles { f -> f.extension == "json" }?.maxByOrNull { it.lastModified() }
}
