package com.theveloper.pixelplay.data.download.jellyfin

import com.theveloper.pixelplay.data.database.SourceType
import com.theveloper.pixelplay.data.download.CloudDownloadSource
import com.theveloper.pixelplay.data.download.DownloadServerCapabilitiesStore
import com.theveloper.pixelplay.data.download.model.CloudDownloadQuality
import com.theveloper.pixelplay.data.download.model.DownloadRequestSpec
import com.theveloper.pixelplay.data.download.model.RemoteItemInfo
import com.theveloper.pixelplay.data.network.jellyfin.JellyfinApiService
import com.theveloper.pixelplay.data.network.jellyfin.JellyfinResponseParser
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * The Jellyfin [CloudDownloadSource] — the first implementation; nothing here is assumed by
 * the engine. Every method here composes [JellyfinApiService]'s own suspend functions, which
 * are already main-safe (`withContext(Dispatchers.IO)` internally) — this class makes no
 * blocking call of its own, so it needs no dispatcher of its own either.
 */
@Singleton
class JellyfinCloudDownloadSource @Inject constructor(
    private val jellyfinApiService: JellyfinApiService,
    private val serverCapabilities: DownloadServerCapabilitiesStore,
) : CloudDownloadSource {

    override val sourceType: Int = SourceType.JELLYFIN

    override fun supports(quality: CloudDownloadQuality): Boolean =
        quality.code == CloudDownloadQuality.MAX.code

    override fun maxConcurrency(quality: CloudDownloadQuality): Int = MAX_CONCURRENT_DOWNLOADS

    override suspend fun isAuthenticated(): Boolean = jellyfinApiService.hasCredentials()

    /**
     * Batched per [batchIdsByUrlBudget]: each batch becomes one
     * [JellyfinApiService.getItemsByIds] call. A failed batch's ids are simply absent from the
     * result — the ones from batches that succeeded are still returned. Only fails outright
     * when every batch failed, so the caller can tell "network is down" from "some items are
     * just missing".
     */
    override suspend fun fetchItemInfo(remoteIds: List<String>): Result<Map<String, RemoteItemInfo>> {
        if (remoteIds.isEmpty()) return Result.success(emptyMap())

        val serverUrl = jellyfinApiService.getServerUrl()
            ?: return Result.failure(IllegalStateException("Jellyfin is not authenticated"))
        val batches = batchIdsByUrlBudget(
            ids = remoteIds,
            baseUrlOverheadBytes = serverUrl.toByteArray(Charsets.UTF_8).size + ITEMS_QUERY_OVERHEAD_BYTES,
        )

        val info = mutableMapOf<String, RemoteItemInfo>()
        var lastFailure: Throwable? = null
        for (batch in batches) {
            jellyfinApiService.getItemsByIds(batch)
                .onSuccess { items ->
                    items.forEach { item -> info[item.itemId()] = item.toRemoteItemInfo() }
                }
                .onFailure { lastFailure = it } // one bad batch never invalidates the rest
        }

        return if (info.isEmpty() && lastFailure != null) {
            Result.failure(lastFailure!!)
        } else {
            Result.success(info)
        }
    }

    /**
     * Probes [JellyfinApiService.checkDownloadPermission] first: a 403 there degrades the spec
     * to [JellyfinApiService.getDirectPlayUrl] instead of throwing.
     * [DownloadRequestSpec.supportsRange] now reads [DownloadServerCapabilitiesStore] (a
     * `null` — never probed, or stale — degrades to `false`, the safe default; only a
     * **confirmed** `true` from a previous probe is ever reported).
     * [DownloadRequestSpec.expectedBytes]/`container`/`mimeType` are left `null`: the caller
     * already has them from an earlier [fetchItemInfo] call, and re-fetching them here would be
     * the exact per-item request batching exists to avoid.
     */
    override suspend fun buildDownloadRequest(
        remoteId: String,
        quality: CloudDownloadQuality,
    ): Result<DownloadRequestSpec> {
        if (!supports(quality)) {
            return Result.failure(
                IllegalArgumentException("JellyfinCloudDownloadSource only supports MAX, got $quality")
            )
        }
        val authorization = jellyfinApiService.getAuthorizationHeader()
            ?: return Result.failure(IllegalStateException("Jellyfin is not authenticated"))
        // Every other method here already assumes credentials are set once we get this far
        // (getAuthorizationHeader already failed above otherwise); an empty key only happens
        // in that same pathological case and just means this row never matches a cached probe.
        val serverKey = jellyfinApiService.getServerUrl().orEmpty()

        return jellyfinApiService.checkDownloadPermission(remoteId).map { permitted ->
            val url = if (permitted) {
                jellyfinApiService.getOriginalDownloadUrl(remoteId)
            } else {
                jellyfinApiService.getDirectPlayUrl(remoteId)
            }
            DownloadRequestSpec(
                url = url,
                headers = mapOf("Authorization" to authorization),
                supportsRange = serverCapabilities.cachedSupportsRange(serverKey) == true,
                expectedBytes = null,
                container = null,
                mimeType = null,
                serverKey = serverKey,
            )
        }
    }

    private fun JSONObject.itemId(): String = optString("Id", "")

    private fun JSONObject.toRemoteItemInfo(): RemoteItemInfo {
        // This uses MediaSources[0]; an item with multiple versions needs verifying against a
        // real server before this can be more than a documented limitation.
        val firstSource = optJSONArray("MediaSources")?.optJSONObject(0)
        val container = firstSource?.optString("Container")?.takeIf { it.isNotBlank() }
        return RemoteItemInfo(
            expectedBytes = firstSource?.optLong("Size", -1L)?.takeIf { it >= 0 },
            container = container,
            mimeType = container?.let(JellyfinResponseParser::containerToMimeType),
        )
    }

    private companion object {
        const val MAX_CONCURRENT_DOWNLOADS = 3
        // "/Users/<uuid>/Items?Fields=MediaSources&Ids=" plus separators — a fixed budget so
        // batchIdsByUrlBudget doesn't need this service's exact path strings.
        const val ITEMS_QUERY_OVERHEAD_BYTES = 200
    }
}

/**
 * Splits [ids] into batches whose resulting URL — [baseUrlOverheadBytes] plus the
 * comma-joined ids of one batch — stays under [maxUrlBytes] (budgeted by URL size, not item
 * count — 300 GUIDs in one URL is over 10 KB, and a default nginx rejects a request line well
 * before that). `internal`: JVM-testable on its own, no network involved.
 */
internal fun batchIdsByUrlBudget(
    ids: List<String>,
    baseUrlOverheadBytes: Int,
    maxUrlBytes: Int = 4_000,
): List<List<String>> {
    if (ids.isEmpty()) return emptyList()
    val batches = mutableListOf<List<String>>()
    var current = mutableListOf<String>()
    var currentBytes = baseUrlOverheadBytes
    for (id in ids) {
        val idBytes = id.toByteArray(Charsets.UTF_8).size + 1 // +1 for the joining comma
        if (current.isNotEmpty() && currentBytes + idBytes > maxUrlBytes) {
            batches.add(current)
            current = mutableListOf()
            currentBytes = baseUrlOverheadBytes
        }
        current.add(id)
        currentBytes += idBytes
    }
    if (current.isNotEmpty()) batches.add(current)
    return batches
}
