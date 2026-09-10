package com.theveloper.pixelplay.data.download.model

/**
 * The eleven internal states a cloud download can be in. The seven states a user actually
 * sees are a separate mapping owned by the UI layer: this enum is the full, unmerged machine
 * the engine itself operates on.
 *
 * A plain `enum class`, not a sealed interface. The usual case for a sealed type is a state
 * where each branch carries *different* data — a `Content(profile)` vs `Error(cause)` shape.
 * Every branch here has the exact same shape: a label with no state-specific payload of its
 * own. The per-row extras a transition needs — `error_code`, `next_retry_at`, the blocking
 * reason — live as their own nullable `cloud_downloads` columns, not embedded in this value,
 * so a plain enum models it correctly: the only requirement is that no invalid combination be
 * representable, and none is, either way, here.
 *
 * The transition table between these states lives in [CloudDownloadStateMachine], not this
 * file. This is only the closed set of values and which of them are terminal.
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
     * only an external trigger can: the user (a manual retry — [FAILED] only moves forward
     * once someone asks it to), the reconciler's periodic pass ([MISSING] and [STALE]
     * self-healing back to [QUEUED]), or a subscribed condition clearing ([BLOCKED] resuming
     * once its blocking reason is gone).
     *
     * [RETRY_WAIT] is deliberately **not** terminal: the engine's own scheduler wakes it via
     * `next_retry_at` without any external trigger, which is exactly what distinguishes it
     * from [BLOCKED] (`BLOCKED` never consumes a retry attempt, `RETRY_WAIT` does).
     */
    val isTerminal: Boolean
        get() = when (this) {
            PENDING, QUEUED, RUNNING, VERIFYING, RETRY_WAIT -> false
            COMPLETED, BLOCKED, FAILED, MISSING, STALE, CANCELLING -> true
        }
}
