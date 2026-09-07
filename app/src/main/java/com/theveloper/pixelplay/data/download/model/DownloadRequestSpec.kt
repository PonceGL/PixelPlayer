package com.theveloper.pixelplay.data.download.model

/**
 * Everything the engine (F1.6) needs to actually make the HTTP request for one download,
 * with zero knowledge of which source produced it — this is the boundary I6 describes.
 *
 * [headers] carries `Authorization`, never a query parameter: a token in the URL ends up in
 * server access logs, proxy logs, and browser history (`AND-SEC-01`, C9).
 *
 * [supportsRange] is what the engine checks before attempting a `Range` request at all —
 * it is never assumed `true` (C10): a wrong guess here is what causes the truncate-and-retry
 * loop C10 exists to prevent. [PLAN.md] §F1 · 4.5 / F1.4b owns the real per-server value; a
 * source that hasn't probed yet reports `false`, the safe default.
 */
data class DownloadRequestSpec(
    val url: String,
    val headers: Map<String, String>,
    val supportsRange: Boolean,
    val expectedBytes: Long?,
    val container: String?,
    val mimeType: String?,
)
