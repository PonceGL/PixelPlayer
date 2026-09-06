package com.theveloper.pixelplay.data.download.model

/**
 * The eleven internal states a cloud download can be in (`PLAN.md` §5.6). The seven states a
 * user actually sees are a separate mapping owned by the UI layer (`PLAN.md` §4.3, F2.5+):
 * this enum is the full, unmerged machine the engine itself operates on.
 *
 * A plain `enum class`, not a sealed interface. `AND-KT-02`'s own example (`ProfileUiState`)
 * is a state where each branch carries *different* data — `Content(profile)` vs
 * `Error(cause)`. Every branch here has the exact same shape: a label with no
 * state-specific payload of its own. The per-row extras a transition needs — `error_code`,
 * `next_retry_at`, the blocking reason — live as their own nullable `cloud_downloads`
 * columns, not embedded in this value (`PLAN.md` §F1.3), so a plain enum models it correctly:
 * `GEN-DES-03` only requires that no invalid combination be representable, and none is,
 * either way, here.
 *
 * The transition table between these states is F1.6a's job, not this file's. This is only
 * the closed set of values and which of them are terminal.
 */
enum class CloudDownloadState {
    PENDING,
    QUEUED,
    RUNNING,
    VERIFYING,
    COMPLETED,
    RETRY_WAIT,
    BLOCKED,
    FAILED,
    MISSING,
    STALE,
    CANCELLING;

    /**
     * `true` when nothing the engine's own queue loop does will move this state forward —
     * only an external trigger can: the user (a manual retry, per the state diagram's own
     * *"[terminal hasta reintento manual]"* annotation on [FAILED]), the reconciler's
     * periodic pass ([MISSING] and [STALE] "autorreparación"), or a subscribed condition
     * clearing ([BLOCKED] resuming once its blocking reason is gone).
     *
     * [RETRY_WAIT] is deliberately **not** terminal: the engine's own scheduler wakes it via
     * `next_retry_at` without any external trigger, which is exactly what distinguishes it
     * from [BLOCKED] (invariant I4: `BLOCKED` never consumes an attempt, `RETRY_WAIT` does).
     */
    val isTerminal: Boolean
        get() = when (this) {
            PENDING, QUEUED, RUNNING, VERIFYING, RETRY_WAIT -> false
            COMPLETED, BLOCKED, FAILED, MISSING, STALE, CANCELLING -> true
        }
}
