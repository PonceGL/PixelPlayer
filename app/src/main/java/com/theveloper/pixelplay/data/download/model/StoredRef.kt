package com.theveloper.pixelplay.data.download.model

/**
 * Which [DownloadStorageBackend][com.theveloper.pixelplay.data.download.storage.DownloadStorageBackend]
 * owns a download's file. Persisted as `cloud_downloads.storage_backend` (`PLAN.md` §F1.3).
 */
enum class DownloadStorageBackendId {
    APP_PRIVATE,
    SAF,
}

/**
 * A published download's file, as its owning backend understands it. Persisted as
 * `cloud_downloads.storage_ref` alongside [backendId] (`PLAN.md` §F1.3): an absolute path for
 * [DownloadStorageBackendId.APP_PRIVATE], a `content://` URI string for
 * [DownloadStorageBackendId.SAF] (F9).
 *
 * Opaque to everyone except the backend that produced it — nobody parses [value]; it's
 * handed back to the same backend's [open][com.theveloper.pixelplay.data.download.storage.DownloadStorageBackend.open],
 * [exists], [sizeOf] and [delete].
 */
data class StoredRef(
    val backendId: DownloadStorageBackendId,
    val value: String,
)
