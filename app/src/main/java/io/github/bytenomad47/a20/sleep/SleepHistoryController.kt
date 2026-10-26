// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.sleep

import android.content.Context
import android.util.AtomicFile
import io.github.bytenomad47.a20.device.CompanionRepository
import io.github.bytenomad47.a20.protocol.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

internal data class SleepHistoryState(
    val loading: Boolean = false,
    val attempted: Boolean = false,
    val syncedAt: Long = 0,
    val records: List<SleepRecord> = emptyList(),
    val sources: String = "",
    val message: String? = null,
)

/** Explicit foreground capture. All-or-nothing save, per-pair isolation and bounded retention. */
internal class SleepHistoryController(
    private val context: Context,
    private val repo: CompanionRepository,
) {
    private val mutable = MutableStateFlow(SleepHistoryState())
    val state = mutable.asStateFlow()
    private var pair: String? = null
    private var document: JSONObject? = null
    private var lastAttempt: JSONObject? = null

    private fun file(address: String): AtomicFile {
        val hash =
            MessageDigest.getInstance("SHA-256")
                .digest(address.uppercase().toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        return AtomicFile(File(context.filesDir, "sleep_history/$hash.json"))
    }

    suspend fun load() =
        withContext(Dispatchers.IO) {
            repo.awaitInitialized()
            val address = repo.settings.value.address
            if (address == pair) return@withContext
            pair = address
            document = null
            lastAttempt = null
            mutable.value = SleepHistoryState()
            if (address != null) {
                val saved = file(address)
                if (saved.baseFile.exists()) {
                    try {
                        val bytes = saved.openRead().use { it.readBytes() }
                        check(bytes.size <= 1_000_000)
                        val json = JSONObject(bytes.toString(Charsets.UTF_8))
                        val records = decode(json)
                        document = json
                        mutable.value =
                            SleepHistoryState(
                                syncedAt = json.getLong("syncedAt"),
                                records = records,
                                sources = json.getString("sources"),
                            )
                    } catch (_: Exception) {
                        mutable.value =
                            SleepHistoryState(
                                message =
                                    "Saved sleep history could not be read. No earbud records were changed."
                            )
                    }
                }
            }
        }

    suspend fun sync() {
        load()
        if (mutable.value.loading) return
        mutable.update { it.copy(loading = true, attempted = true, message = null) }
        try {
            repo.withSleepHistory { address, result ->
                check(address == pair) { "Selected device changed." }
                val incoming = result.values.flatten().flatMap { it.records }
                val merged = (mutable.value.records + incoming).distinct().takeLast(3000)
                val sources =
                    result.entries.joinToString("; ") { (kind, values) ->
                        "${kind.name}: " +
                            values.joinToString { if (it.source == 0) "Left" else "Right" }
                    }
                val now = System.currentTimeMillis()
                val json = encode(now, merged, sources)
                withContext(Dispatchers.IO) {
                    val target = file(address)
                    target.baseFile.parentFile?.mkdirs()
                    val output = target.startWrite()
                    try {
                        output.write(json.toString(2).toByteArray())
                        target.finishWrite(output)
                    } catch (e: Exception) {
                        target.failWrite(output)
                        throw e
                    }
                }
                document = json
                lastAttempt = json
                mutable.value =
                    SleepHistoryState(
                        attempted = true,
                        syncedAt = now,
                        records = merged,
                        sources = sources,
                        message =
                            if (incoming.isEmpty())
                                "No saved records were returned. Earlier imports are kept."
                            else "Sleep records saved on this phone.",
                    )
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            lastAttempt =
                JSONObject()
                    .put("format", 1)
                    .put("attemptedAt", System.currentTimeMillis())
                    .put("complete", false)
                    .put("errorType", e.javaClass.simpleName)
                    .put("stage", repo.state.value.message)
                    .put(
                        "reason",
                        if (e is ProtocolException) e.message
                        else "Connection, qualification or local save failed",
                    )
                    .put(
                        "scope",
                        "Failed read-only sleep sync. Earlier saved history was retained. No erase, tracking toggle or clock write.",
                    )
            mutable.update {
                it.copy(
                    message =
                        "Sleep sync did not complete. Check the connection and firmware 01.57, then try again. Earlier imports are kept."
                )
            }
        } finally {
            mutable.update { it.copy(loading = false) }
        }
    }

    suspend fun deleteLocal() =
        withContext(Dispatchers.IO) {
            check(!mutable.value.loading)
            pair?.let { file(it).delete() }
            document = null
            lastAttempt = null
            mutable.value =
                SleepHistoryState(
                    message = "Local sleep history deleted. Earbud records are unchanged."
                )
        }

    fun export(): String =
        (lastAttempt ?: document ?: error("Sync sleep history first")).toString(2)

    companion object {
        internal fun encode(now: Long, records: List<SleepRecord>, sources: String): JSONObject =
            JSONObject()
                .put("format", 1)
                .put("syncedAt", now)
                .put("sources", sources)
                .put(
                    "scope",
                    "Read-only A6611/01.57 sleep-record preview. No clear, tracking toggle or clock write. Timestamps and sleep classifications remain unqualified. Contains personal sleep data; review before sharing.",
                )
                .put(
                    "records",
                    JSONArray().also { array ->
                        records.forEach { record ->
                            array.put(
                                JSONObject()
                                    .put("kind", record.kind.name)
                                    .put("source", record.source)
                                    .put("hex", record.hex)
                            )
                        }
                    },
                )

        internal fun decode(json: JSONObject): List<SleepRecord> {
            check(json.getInt("format") == 1 && json.getLong("syncedAt") > 0)
            val rows = json.getJSONArray("records")
            check(rows.length() <= 3000)
            return (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                SleepRecord(
                    SleepRecordKind.valueOf(row.getString("kind")),
                    row.getInt("source"),
                    row.getString("hex"),
                )
            }
        }
    }
}
