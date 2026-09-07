package com.theveloper.pixelplay.data.download.model

import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.BLOCKED
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.CANCELLING
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.COMPLETED
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.FAILED
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.MISSING
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.PENDING
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.QUEUED
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.RETRY_WAIT
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.RUNNING
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.STALE
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.VERIFYING
import timber.log.Timber

/**
 * The transition table over [CloudDownloadState] (`F1.6a`, `PLAN.md` §5.6). [CloudDownloadState]
 * itself only owns the closed set of values; this is the graph of which of them can follow which.
 *
 * Every edge below is traced straight from the §5.6 diagram — no edge is added on a hunch, and
 * none of the diagram's edges is dropped:
 * ```
 * PENDING ──encolar──▶ QUEUED ──hueco──▶ RUNNING ──▶ VERIFYING ──▶ COMPLETED
 *                        ▲                  │
 *                        │                  ├─▶ RETRY_WAIT(next_retry_at) ──┐
 *                        └──────────────────┤                              │
 *                                           ├─▶ BLOCKED(motivo)            │ condición
 *                                           │                              │ cumplida
 *                                           └─▶ FAILED(código)  [terminal hasta reintento manual]
 *
 * COMPLETED ──archivo ausente / volumen fuera──▶ MISSING ──(autorreparación)──▶ QUEUED
 * COMPLETED ──requested_quality != quality─────▶ STALE   ─────────────────────▶ QUEUED
 * cualquiera ─────────────────────────────────▶ CANCELLING ──▶ fila y archivos eliminados
 * ```
 *
 * `RUNNING`/`VERIFYING` failing is a `RETRY_WAIT`/`BLOCKED`/`FAILED` fork — the diagram draws it
 * once, after `RUNNING`, but `VERIFYING` is the same kind of attempt (F1.6b's checksum/size
 * verification can fail exactly as a transfer can) and gets the identical fork. `RETRY_WAIT`,
 * `BLOCKED`, `FAILED`, `MISSING` and `STALE` all resolve back to `QUEUED` — never straight to
 * `RUNNING` — because re-entering `RUNNING` means competing for one of the engine's concurrency
 * slots again (`hueco`, F1.6c), not resuming for free.
 */
object CloudDownloadStateMachine {

    /**
     * Edges drawn directly from the diagram, before the universal "→ `CANCELLING`" rule is
     * folded in by [legalTransitions]. `CANCELLING` itself has none: once cancelled, the row is
     * deleted (`PLAN.md` §5.6) — there is nothing left in this enum to transition *from*.
     */
    private val explicitTransitions: Map<CloudDownloadState, Set<CloudDownloadState>> = mapOf(
        PENDING to setOf(QUEUED),
        QUEUED to setOf(RUNNING),
        RUNNING to setOf(VERIFYING, RETRY_WAIT, BLOCKED, FAILED),
        VERIFYING to setOf(COMPLETED, RETRY_WAIT, BLOCKED, FAILED),
        COMPLETED to setOf(MISSING, STALE),
        RETRY_WAIT to setOf(QUEUED),
        BLOCKED to setOf(QUEUED),
        FAILED to setOf(QUEUED),
        MISSING to setOf(QUEUED),
        STALE to setOf(QUEUED),
        CANCELLING to emptySet(),
    )

    /**
     * [explicitTransitions] plus the diagram's own "`cualquiera` → `CANCELLING`" rule, folded in
     * structurally rather than repeated by hand for every source state — the same reason a new
     * state added here later can't forget it.
     */
    private val legalTransitions: Map<CloudDownloadState, Set<CloudDownloadState>> =
        explicitTransitions.mapValues { (from, targets) ->
            if (from == CANCELLING) targets else targets + CANCELLING
        }

    /** Whether `§5.6` allows moving directly from [from] to [to]. Never true for `from == to`. */
    fun isLegal(from: CloudDownloadState, to: CloudDownloadState): Boolean =
        to in legalTransitions.getValue(from)

    /**
     * Applies the transition if [isLegal], per `GEN-ERR-06`: an illegal one **throws** when
     * [throwOnIllegal] is true (defaults to [BuildConfig.DEBUG] — fail fast in development) and
     * otherwise **degrades**, logging the attempt and returning [from] unchanged rather than
     * accepting a value `§5.6` never sanctioned. [throwOnIllegal] is a parameter, not a hardcoded
     * `BuildConfig.DEBUG` read, so both branches are exercised by a JVM test regardless of which
     * build variant runs it.
     */
    fun transition(
        from: CloudDownloadState,
        to: CloudDownloadState,
        throwOnIllegal: Boolean = BuildConfig.DEBUG,
    ): CloudDownloadState {
        if (isLegal(from, to)) return to

        val message = "Illegal cloud download transition: $from -> $to"
        if (throwOnIllegal) {
            throw IllegalStateException(message)
        }
        Timber.e(message)
        return from
    }
}
