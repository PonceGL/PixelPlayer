package com.theveloper.pixelplay.data.download

import com.theveloper.pixelplay.data.download.model.CloudDownloadQuality
import com.theveloper.pixelplay.data.download.model.DownloadRequestSpec
import com.theveloper.pixelplay.data.download.model.RemoteItemInfo

/**
 * Translates "I want remote item X" into "make this HTTP request" for one cloud source —
 * the boundary that keeps the engine (F1.6) from ever knowing Jellyfin exists (invariant I6).
 * Jellyfin is the first implementation; nothing here is Jellyfin-specific (D-01).
 *
 * Registered via a Hilt `@IntoMap` multibinding keyed by [sourceType] (`SourceType`, C6) — not
 * an enum (R15), so nothing here gets compiler-enforced exhaustiveness; a tabular test over
 * the seven `SourceType` constants is the only net.
 *
 * **Narrower than `PLAN.md` §F1.4's original proposal, on purpose (D-28: propuesta, no
 * contrato).** Two methods from that proposal are missing here:
 * - `resolveCollectionMembers(type: CollectionType, collectionId: String)` needs
 *   `CollectionType`, a type F4 (colecciones) hasn't defined yet — F1 has no collections at
 *   all (single-song downloads only), so there is no caller for it yet either.
 * - `classifyError(e: Throwable, httpCode: Int?): CloudDownloadError` needs `CloudDownloadError`,
 *   which is explicitly F3.3's model (`F1.md` §F1.6: *"en F1 basta con distinguir
 *   'reintentable' de 'no'"*).
 *
 * Inventing placeholder types for either now, just to satisfy a wider interface no F1 code
 * calls, is exactly what `GEN-DES-13` (YAGNI) prohibits — and F3/F4 would very likely have to
 * redesign both once their real requirements are known anyway. Both get added to this
 * interface as part of F3.3 and F4, respectively.
 */
interface CloudDownloadSource {

    /** One of the `SourceType` constants (`data/database/SongEntity.kt`) — never a raw literal. */
    val sourceType: Int

    /** Whether this source can deliver [quality] at all. Jellyfin: only [CloudDownloadQuality.MAX] (D-02). */
    fun supports(quality: CloudDownloadQuality): Boolean

    /** How many downloads from this source the engine may run at once for [quality]. */
    fun maxConcurrency(quality: CloudDownloadQuality): Int

    /** Whether this source currently has a usable session — no network call implied. */
    suspend fun isAuthenticated(): Boolean

    /**
     * Batch lookup of [RemoteItemInfo] for [remoteIds], keyed by remote id. An id this source
     * couldn't get info for (its batch failed, C14) is simply absent from the map — never a
     * placeholder entry, never a reason to fail the ids whose batch *did* succeed. Fails the
     * whole call only when **every** batch failed.
     */
    suspend fun fetchItemInfo(remoteIds: List<String>): Result<Map<String, RemoteItemInfo>>

    /** Builds the request to actually download [remoteId] at [quality]. */
    suspend fun buildDownloadRequest(remoteId: String, quality: CloudDownloadQuality): Result<DownloadRequestSpec>
}
