// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.find

import io.github.bytenomad47.a20.device.FindNotSentException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FindTarget {
    LEFT,
    RIGHT,
    BOTH,
}

enum class FindPhase {
    IDLE,
    STARTING,
    PLAYING,
    STOPPING,
    UNCERTAIN,
}

data class FindState(
    val phase: FindPhase = FindPhase.IDLE,
    val target: FindTarget? = null,
    val seconds: Int = 0,
    val note: String? = null,
)

/**
 * Gateway must hold normal-action exclusion until stop and use only qualified A6611 commands.
 * Qualification covers the pinned wire mapping. The phone deadline does not imply a native timeout.
 */
internal interface FindGateway {
    val qualified: Boolean

    suspend fun start(target: FindTarget)

    suspend fun stop(target: FindTarget)

    suspend fun release() {}
}

internal interface FindReceipt {
    fun pending(): FindTarget?

    fun save(target: FindTarget?)
}

/** Starts are never retried; an unresolved receipt can only cause a stop on recovery. */
internal class FindSession(
    private val gateway: FindGateway,
    private val receipt: FindReceipt,
    private val scope: CoroutineScope,
) {
    private val mutable =
        MutableStateFlow(
            FindState(
                if (receipt.pending() != null) FindPhase.UNCERTAIN else FindPhase.IDLE,
                receipt.pending(),
            )
        )
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var timer: Job? = null
    private var starting: Job? = null
    val qualified
        get() = gateway.qualified

    fun start(target: FindTarget, confirmed: Boolean) {
        if (!confirmed || !qualified || state.value.phase != FindPhase.IDLE) return
        val epoch = ++generation
        try {
            receipt.save(target)
        } catch (_: Exception) {
            mutable.value =
                FindState(note = "Could not save the find request. No sound was requested.")
            return
        } // Never send before durable uncertainty is recorded.
        mutable.value = FindState(FindPhase.STARTING, target, 30)
        timer = scope.launch {
            repeat(30) {
                delay(1000)
                mutable.value = mutable.value.copy(seconds = 29 - it)
            }
            stop()
        }
        starting = scope.launch {
            try {
                withTimeout(5000) { gateway.start(target) }
                if (epoch != generation) return@launch
                mutable.value = mutable.value.copy(phase = FindPhase.PLAYING)
            } catch (failure: FindNotSentException) {
                if (epoch == generation) {
                    timer?.cancel()
                    receipt.save(null)
                    mutable.value = FindState(note = failure.message)
                }
            } catch (_: Exception) {
                if (epoch == generation)
                    mutable.value = mutable.value.copy(phase = FindPhase.UNCERTAIN)
            }
        }
    }

    fun stop() {
        val target = state.value.target ?: return
        if (state.value.phase == FindPhase.STOPPING) return
        val stopEpoch = ++generation
        timer?.cancel()
        starting?.cancel()
        mutable.value = FindState(FindPhase.STOPPING, target)
        scope.launch {
            try {
                withTimeout(5000) { gateway.stop(target) }
                if (stopEpoch != generation) return@launch
                receipt.save(null)
                mutable.value = FindState(note = "Stop request accepted")
            } catch (_: Exception) {
                mutable.value = FindState(FindPhase.UNCERTAIN, target)
            }
        }
    }

    fun deviceStopped() {
        if (state.value.phase == FindPhase.IDLE) return
        if (state.value.phase == FindPhase.STARTING) {
            stop()
            return
        }
        ++generation
        timer?.cancel()
        starting?.cancel()
        mutable.value = FindState(FindPhase.STOPPING, state.value.target)
        scope.launch {
            try {
                gateway.release()
                receipt.save(null)
                mutable.value = FindState(note = "Stopped using earbud controls")
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(phase = FindPhase.UNCERTAIN)
            }
        }
    }

    fun disconnected() {
        if (state.value.phase == FindPhase.IDLE) return
        ++generation
        timer?.cancel()
        starting?.cancel()
        mutable.value = FindState(FindPhase.UNCERTAIN, state.value.target)
    }

    fun leave() {
        if (state.value.phase != FindPhase.IDLE) stop()
    }

    fun reconnect() {
        if (state.value.phase == FindPhase.UNCERTAIN && receipt.pending() != null) stop()
    }
}
