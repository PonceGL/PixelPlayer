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
 *
 * [serverKey] (`F1.6b`) is the same opaque, token-free identity `F1.4b`'s
 * `DownloadServerCapabilitiesStore` keys on — added here rather than derived from [url] by the
 * engine, because the engine (`data/download/engine/`) cannot derive it correctly without
 * knowing the source's own URL layout (a self-hosted Jellyfin at a subpath, `F1.md` §F1.4 case
 * borde 5, would lose that subpath if the engine just stripped everything after the host) —
 * and deriving it *with* that knowledge would violate I6. Only the source that built [url]
 * already has the real credentials to get this right.
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
