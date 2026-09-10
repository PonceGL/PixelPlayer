package com.theveloper.pixelplay.data.download.model

/**
 * Live, in-memory progress of one cloud download. Never persisted:
 * `cloud_downloads.downloaded_bytes` only gets a coarse resume point every so often, from the
 * download engine — this is the fine-grained value a progress bar actually reads.
 *
 * [bytesPerSecond] and [etaMillis] are computed over a **sliding window**, not the download's
 * cumulative average: an average-based ETA only ever goes down and never reacts to the
 * network getting better or worse, which is useless exactly when someone looks at it.
 *
 * Both are `null`, never `NaN`/`Infinity`, when they can't be computed yet — not enough
 * samples in the window — and [bytesPerSecond] is `0`, with [etaMillis] `null`, when [state]
 * is terminal (paused, blocked, failed, ...): a stalled download has a real, reportable speed
 * of zero, but no meaningful time-to-completion.
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
     * `null` when [expectedBytes] is unknown — a row from before the `size` column existed —
     * meaning an indeterminate progress bar, never a fraction that quietly reads 0%.
     */
    val fraction: Float?
        get() = expectedBytes
            ?.takeIf { it > 0 }
            ?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
}
