// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.device

import io.github.bytenomad47.a20.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

interface DeviceGateway {
    val playbackEvents: Flow<SleepPlaybackStatus>
        get() = emptyFlow()

    suspend fun readState(): DeviceSnapshot

    suspend fun setFind(left: Boolean, right: Boolean) {
        throw ProtocolException("Find unsupported")
    }

    suspend fun setSleepPlayback(slot: Int, playing: Boolean) {
        throw ProtocolException("Sleep playback unsupported")
    }

    suspend fun setMode(mode: Mode)

    suspend fun setSleepVolume(level: Int) {
        throw ProtocolException("Sleep volume unsupported")
    }
}

enum class ActionCode {
    SUCCESS,
    BUSY,
    UNVERIFIED,
    UNAVAILABLE,
    UNSUPPORTED,
    PERMISSION_REQUIRED,
    TIMEOUT,
    FAILED,
}

data class ActionResult(val code: ActionCode, val message: String) {
    val successful
        get() = code == ActionCode.SUCCESS
}

/** All callers share one runner. It has no queue and never replays an operation. */
class ActionCoordinator(
    private val gateway: DeviceGateway,
    private val modeVerified: Boolean = ModeCapability.VERIFIED,
    private val deadlineMs: Long = 10_000,
) {
    private val mutex = Mutex()

    suspend fun execute(action: DeviceAction): ActionResult {
        if (!mutex.tryLock())
            return ActionResult(ActionCode.BUSY, "An A20 action is already running.")
        try {
            if (action != DeviceAction.Refresh && !modeVerified)
                return ActionResult(ActionCode.UNVERIFIED, ModeCapability.REASON)
            return withTimeout(deadlineMs) {
                val before = gateway.readState()
                if (action == DeviceAction.Refresh)
                    return@withTimeout ActionResult(ActionCode.SUCCESS, "Status received from A20.")
                if (!ModeCapability.supportsModeControl(before))
                    return@withTimeout ActionResult(
                        ActionCode.UNSUPPORTED,
                        "Mode control requires firmware 01.57 on the active A20 earbud and any linked peer.",
                    )
                if (action == DeviceAction.ToggleSleepPlayback) {
                    val slot = before.sleepSoundSlot
                    val playing = before.sleepPlaying
                    if (
                        before.mode != Mode.SLEEP ||
                            slot == null ||
                            playing == null ||
                            before.sleepSlots?.contains(slot) != true
                    )
                        return@withTimeout ActionResult(
                            ActionCode.UNSUPPORTED,
                            "Sleep playback requires Sleep mode and a valid saved sound. Pull down to refresh.",
                        )
                    val desired = !playing
                    var ambiguous = false
                    try {
                        gateway.setSleepPlayback(slot, desired)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: ModeRejectedException) {
                        return@withTimeout ActionResult(
                            ActionCode.FAILED,
                            "A20 refused the Sleep playback change.",
                        )
                    } catch (_: Exception) {
                        ambiguous = true
                    }
                    // A toggle is derived only from this request's fresh pre-read. Never replay it.
                    repeat(6) { attempt ->
                        if (attempt > 0) delay(500)
                        val after = gateway.readState()
                        if (
                            after.mode == Mode.SLEEP &&
                                after.sleepSoundSlot == slot &&
                                after.sleepPlaying == desired
                        )
                            return@withTimeout ActionResult(
                                ActionCode.SUCCESS,
                                "A20 confirmed Sleep sounds ${if (desired) "playing" else "paused"}.",
                            )
                    }
                    return@withTimeout ActionResult(
                        ActionCode.FAILED,
                        if (ambiguous)
                            "Sleep playback outcome is unconfirmed. Pull down to refresh before retrying."
                        else "The earbuds did not confirm the Sleep playback change.",
                    )
                }
                if (action is DeviceAction.SetSleepVolume) {
                    if (before.mode != Mode.SLEEP || before.sleepVolume == null)
                        return@withTimeout ActionResult(
                            ActionCode.UNSUPPORTED,
                            "Sleep sound volume requires Sleep mode and a supported volume reading.",
                        )
                    if (before.sleepVolume == action.level)
                        return@withTimeout ActionResult(
                            ActionCode.SUCCESS,
                            "A20 already reports the requested Sleep volume.",
                        )
                    var ambiguous = false
                    try {
                        gateway.setSleepVolume(action.level)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: ModeRejectedException) {
                        return@withTimeout ActionResult(
                            ActionCode.FAILED,
                            "A20 refused the Sleep volume change.",
                        )
                    } catch (_: Exception) {
                        ambiguous = true
                    }
                    repeat(6) { attempt ->
                        if (attempt > 0) delay(500)
                        val after = gateway.readState()
                        if (after.mode == Mode.SLEEP && after.sleepVolume == action.level)
                            return@withTimeout ActionResult(
                                ActionCode.SUCCESS,
                                "A20 confirmed Sleep volume ${action.level}/65.",
                            )
                    }
                    return@withTimeout ActionResult(
                        ActionCode.FAILED,
                        if (ambiguous)
                            "Sleep volume outcome is unconfirmed. Pull down to refresh before retrying."
                        else "The earbuds did not confirm the requested Sleep volume.",
                    )
                }
                val desired =
                    when (action) {
                        is DeviceAction.SetMode -> action.mode
                        DeviceAction.ToggleMode ->
                            when (before.mode) {
                                Mode.SLEEP -> Mode.BLUETOOTH
                                Mode.BLUETOOTH -> Mode.SLEEP
                                null ->
                                    return@withTimeout ActionResult(
                                        ActionCode.UNSUPPORTED,
                                        "The earbuds did not report their current mode.",
                                    )
                            }
                        else -> error("Unhandled action")
                    }
                if (before.mode == desired)
                    return@withTimeout ActionResult(
                        ActionCode.SUCCESS,
                        "A20 already reports ${desired.wireName} mode.",
                    )
                // No retry after an ambiguous write: only a readback is attempted.
                var ambiguous = false
                try {
                    gateway.setMode(desired)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: ModeRejectedException) {
                    return@withTimeout ActionResult(
                        ActionCode.FAILED,
                        "A20 refused the mode change. Check that the earbuds are awake and no firmware update is running.",
                    )
                } catch (_: Exception) {
                    ambiguous = true
                }
                // 01.57 can defer its mode transition briefly. Poll status only; never resend.
                repeat(6) { attempt ->
                    if (attempt > 0) delay(500)
                    val after = gateway.readState()
                    if (after.mode == desired)
                        return@withTimeout ActionResult(
                            ActionCode.SUCCESS,
                            "A20 confirmed ${desired.wireName} mode.",
                        )
                }
                ActionResult(
                    ActionCode.FAILED,
                    if (ambiguous)
                        "Mode change outcome is unconfirmed. Refresh status before trying again."
                    else "The earbuds did not confirm the requested mode.",
                )
            }
        } catch (_: TimeoutCancellationException) {
            return ActionResult(
                ActionCode.TIMEOUT,
                if (action == DeviceAction.Refresh)
                    "A20 did not respond within ten seconds. No retry is scheduled."
                else
                    "The A20 change could not be confirmed within ten seconds. Refresh before retrying; no automatic retry is scheduled.",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            return ActionResult(
                ActionCode.PERMISSION_REQUIRED,
                "Nearby devices permission is required.",
            )
        } catch (_: ProtocolException) {
            return ActionResult(
                ActionCode.UNSUPPORTED,
                "This A20 response is not supported. Save a local diagnostic report.",
            )
        } catch (_: Exception) {
            return ActionResult(
                ActionCode.UNAVAILABLE,
                "A20 is unavailable. Check Bluetooth, take a bud out of the case, and close Soundcore if it holds the control connection.",
            )
        } finally {
            mutex.unlock()
        }
    }
}
