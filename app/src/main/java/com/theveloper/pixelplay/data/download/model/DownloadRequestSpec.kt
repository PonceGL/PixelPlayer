package com.theveloper.pixelplay.data.download.model

/**
 * Everything the download engine needs to actually make the HTTP request for one download,
 * with zero knowledge of which source produced it.
 *
 * [headers] carries `Authorization`, never a query parameter: a token in the URL ends up in
 * server access logs, proxy logs, and browser history.
 *
 * [supportsRange] is what the engine checks before attempting a `Range` request at all —
 * it is never assumed `true`: a wrong guess here is what causes the truncate-and-retry loop
 * this field exists to prevent. [DownloadServerCapabilitiesStore] owns the real per-server
 * value; a source that hasn't probed yet reports `false`, the safe default.
 *
 * [serverKey] is the same opaque, token-free identity [DownloadServerCapabilitiesStore] keys
 * on — added here rather than derived from [url] by the engine, because the engine cannot
 * derive it correctly without knowing the source's own URL layout (a self-hosted Jellyfin at
 * a subpath would lose that subpath if the engine just stripped everything after the host) —
 * and deriving it *with* that knowledge would mean the engine has to understand a specific
 * source's URL scheme, which it must never do. Only the source that built [url] already has
 * the real credentials to get this right.
 */
data class DownloadRequestSpec(
    val url: String,
    val headers: Map<String, String>,
    val supportsRange: Boolean,
    val expectedBytes: Long?,
    val container: String?,
    val mimeType: String?,
    val serverKey: String,
)
