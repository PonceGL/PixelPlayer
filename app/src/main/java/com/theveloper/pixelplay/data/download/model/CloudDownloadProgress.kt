package com.theveloper.pixelplay.data.download.model

/**
 * Live, in-memory progress of one cloud download. Never persisted (invariant I3, `PLAN.md`
 * §5.2): `cloud_downloads.downloaded_bytes` only gets a coarse resume point every so often,
 * from the engine (F1.6c) — this is the fine-grained value a progress bar actually reads.
 *
 * [bytesPerSecond] and [etaMillis] are computed over a **sliding window**, not the download's
 * cumulative average: an average-based ETA only ever goes down and never reacts to the
 * network getting better or worse, which is useless exactly when someone looks at it
 * (`PLAN.md` §F1.7 aceptación).
 *
 * Both are `null`, never `NaN`/`Infinity`, when they can't be computed yet — not enough
 * samples in the window — and [bytesPerSecond] is `0`, with [etaMillis] `null`, when [state]
 * is terminal (paused, blocked, failed, ...): a stalled download has a real, reportable speed
 * of zero, but no meaningful time-to-completion (`PLAN.md` §F1 · F1.7, case borde 2).
 */
data class CloudDownloadProgress(
    val key: CloudDownloadKey,
    val state: CloudDownloadState,
    val downloadedBytes: Long,
    val expectedBytes: Long?,
    val bytesPerSecond: Long? = null,
    val etaMillis: Long? = null,
) {
    /**
     * `null` when [expectedBytes] is unknown — a row from before the `size` column existed
     * (R7) — meaning an indeterminate progress bar, never a fraction that quietly reads 0%.
     */
    val fraction: Float?
        get() = expectedBytes
            ?.takeIf { it > 0 }
            ?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
}
