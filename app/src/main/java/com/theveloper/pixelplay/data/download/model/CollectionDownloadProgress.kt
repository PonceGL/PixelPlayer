package com.theveloper.pixelplay.data.download.model

/**
 * Aggregated progress for a subscription's downloads — the shape banners read. Deliberately
 * the **same field shape as
 * `com.theveloper.pixelplay.data.worker.SyncManager.SyncProgress`** so
 * `com.theveloper.pixelplay.presentation.components.SyncProgressBar` can render this without a
 * dedicated adapter composable — a call site just copies these four fields across.
 *
 * [currentCount] counts members whose latest known state is
 * [com.theveloper.pixelplay.data.download.model.CloudDownloadState.COMPLETED] — not "has a live
 * progress entry", since a member can be queued, running or blocked without being done.
 */
data class CollectionDownloadProgress(
    val isRunning: Boolean = false,
    val currentCount: Int = 0,
    val totalCount: Int = 0,
    val isCompleted: Boolean = false,
) {
    val progress: Float
        get() = if (totalCount > 0) currentCount.toFloat() / totalCount else 0f

    val hasProgress: Boolean
        get() = totalCount > 0
}
