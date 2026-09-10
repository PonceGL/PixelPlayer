package com.theveloper.pixelplay.data.download

import com.theveloper.pixelplay.data.download.model.CloudDownloadQuality
import com.theveloper.pixelplay.data.download.model.DownloadRequestSpec
import com.theveloper.pixelplay.data.download.model.RemoteItemInfo

/**
 * Translates "I want remote item X" into "make this HTTP request" for one cloud source —
 * the boundary that keeps the download engine from ever knowing Jellyfin exists. Jellyfin
 * is the first implementation; nothing here is Jellyfin-specific.
 *
 * Registered via a Hilt `@IntoMap` multibinding keyed by [sourceType] (a `SourceType`
 * constant) — not an enum, so nothing here gets compiler-enforced exhaustiveness; a tabular
 * test over the `SourceType` constants is the only net.
 *
 * **Intentionally narrower than a "full" cloud-source interface.** Two methods that a more
 * complete design would include are deliberately missing here:
 * - `resolveCollectionMembers(type: CollectionType, collectionId: String)` needs a
 *   `CollectionType` this codebase doesn't define yet — collection downloads (playlists,
 *   albums) aren't supported yet either, so there is no caller for it.
 * - `classifyError(e: Throwable, httpCode: Int?): CloudDownloadError` needs a
 *   `CloudDownloadError` model finer than what callers currently need: today it's enough to
 *   tell "retryable" apart from "not".
 *
 * Inventing placeholder types for either now, just to satisfy a wider interface nothing yet
 * calls, buys nothing and risks a redesign once collection downloads and finer error
 * classification actually land — which is when both get added to this interface.
 */
interface CloudDownloadSource {

    /** One of the `SourceType` constants (`data/database/SongEntity.kt`) — never a raw literal. */
    val sourceType: Int

    /** Whether this source can deliver [quality] at all. Jellyfin: only [CloudDownloadQuality.MAX]. */
    fun supports(quality: CloudDownloadQuality): Boolean

    /** How many downloads from this source the engine may run at once for [quality]. */
    fun maxConcurrency(quality: CloudDownloadQuality): Int

    /** Whether this source currently has a usable session — no network call implied. */
    suspend fun isAuthenticated(): Boolean

    /**
     * Batch lookup of [RemoteItemInfo] for [remoteIds], keyed by remote id. An id this source
     * couldn't get info for (its batch failed) is simply absent from the map — never a
     * placeholder entry, never a reason to fail the ids whose batch *did* succeed. Fails the
     * whole call only when **every** batch failed.
     */
    suspend fun fetchItemInfo(remoteIds: List<String>): Result<Map<String, RemoteItemInfo>>

    /** Builds the request to actually download [remoteId] at [quality]. */
    suspend fun buildDownloadRequest(remoteId: String, quality: CloudDownloadQuality): Result<DownloadRequestSpec>
}
