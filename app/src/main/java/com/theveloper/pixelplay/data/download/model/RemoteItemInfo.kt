package com.theveloper.pixelplay.data.download.model

/**
 * What [com.theveloper.pixelplay.data.download.CloudDownloadSource.fetchItemInfo] knows about
 * one remote item, straight from the source's own batch lookup — never from a local cache
 * (`cloud_downloads`/`jellyfin_songs` stay untouched here, I6/I7).
 *
 * [expectedBytes] is `null` when the source didn't report a size for this item (`PLAN.md`
 * §F1 · F1.4, case borde 3 — a recently added item Jellyfin hasn't analyzed yet): the caller
 * treats that as an estimate-only download, never as a hard failure.
 */
data class RemoteItemInfo(
    val expectedBytes: Long?,
    val container: String?,
    val mimeType: String?,
)
