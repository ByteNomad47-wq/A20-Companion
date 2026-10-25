// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.find

import android.content.Context
import io.github.bytenomad47.a20.device.CompanionRepository
import io.github.bytenomad47.a20.device.Connection
import io.github.bytenomad47.a20.protocol.FindProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Private per-selected-device journal. Never exported and never restarts a sound. */
internal class PersistentFindReceipt(context: Context, private val address: () -> String?) :
    FindReceipt {
    private val prefs = context.getSharedPreferences("find_session_v1", Context.MODE_PRIVATE)

    override fun pending(): FindTarget? =
        prefs.getString("target", null)?.let {
            runCatching { FindTarget.valueOf(it) }.getOrDefault(FindTarget.BOTH)
        }

    fun checkDevice() {
        check(pending() == null || prefs.getString("address", null) == address()) {
            "Return to the A20 used for the unresolved find session."
        }
    }

    override fun save(target: FindTarget?) {
        if (target == null) check(prefs.edit().clear().commit())
        else {
            val selected = address() ?: error("Choose your A20 first")
            check(
                prefs
                    .edit()
                    .putString("address", selected)
                    .putString("target", target.name)
                    .commit()
            )
        }
    }
}

internal fun createFindSession(context: Context, repository: CompanionRepository): FindSession {
    val receipt = PersistentFindReceipt(context) { repository.settings.value.address }
    repository.restoreFindPending(receipt.pending() != null)
    val gateway =
        object : FindGateway {
            override val qualified = FindProtocol.WIRE_MAPPING_VERIFIED

            override suspend fun start(target: FindTarget) {
                receipt.checkDevice()
                repository.beginFind(target != FindTarget.RIGHT, target != FindTarget.LEFT)
            }

            override suspend fun stop(target: FindTarget) {
                receipt.checkDevice()
                repository.endFind()
            }

            override suspend fun release() {
                repository.finishFindNotification()
            }
        }
    val session = FindSession(gateway, receipt, repository.scope)
    repository.onFindStopped = { session.deviceStopped() }
    repository.onFindDisconnected = {
        if (session.state.value.phase != FindPhase.IDLE) {
            session.disconnected()
            repository.scope.launch { repository.releaseFindOnDisconnect() }
        }
    }
    repository.scope.launch {
        repository.awaitInitialized()
        repository.state
            .map { Triple(it.connection, it.busy, repository.settings.value.address) }
            .distinctUntilChanged()
            .collect { (connection, busy, _) ->
                if (
                    connection == Connection.CONNECTED &&
                        !busy &&
                        session.state.value.phase == FindPhase.UNCERTAIN
                )
                    session.reconnect()
            }
    }
    return session
}
