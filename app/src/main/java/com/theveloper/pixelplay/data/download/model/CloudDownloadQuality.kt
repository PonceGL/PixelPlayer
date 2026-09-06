package com.theveloper.pixelplay.data.download.model

/**
 * A quality level a cloud download can be requested at: what codec/container/bitrate/
 * extension to ask the source for, if any. Modelled as data — not a bare enum constant —
 * so a transcoded tier is a new entry with real values, never a rewrite of every call site
 * that assumed exactly one shape (`PLAN.md` §F1.4, §F12).
 *
 * [MAX] is the only value while D-02 holds: every field is `null`, meaning "don't specify
 * anything — hand over the original file". Jellyfin's transcoded tiers (parked, F12) would
 * carry real values instead — e.g. `codec = "aac", bitrate = 192, container = "mp4",
 * extension = "m4a"` — that a [com.theveloper.pixelplay.data.download.CloudDownloadSource]
 * turns into transcoding parameters. `PLAN.md` §F12 is explicit that resuming that work needs
 * no change to this shape, only a new entry in [ALL].
 *
 * [code] is what `cloud_downloads.requested_quality` / `.quality` actually persist (both
 * `INTEGER` columns, `PLAN.md` §F1.3): a stable identifier, not the whole data class.
 */
data class CloudDownloadQuality(
    val code: Int,
    val codec: String?,
    val container: String?,
    val bitrate: Int?,
    val extension: String?,
) {
    companion object {
        /** The literal copy (D-02): no transcoding, so no target codec/container/bitrate. */
        val MAX = CloudDownloadQuality(
            code = 0,
            codec = null,
            container = null,
            bitrate = null,
            extension = null,
        )

        /** Every quality level this build knows about. Just [MAX] until F12 is unparked. */
        val ALL: List<CloudDownloadQuality> = listOf(MAX)

        /**
         * Resolves a persisted [code] back to its [CloudDownloadQuality], or `null` if this
         * build doesn't recognize it — e.g. a row written by a newer version that added a
         * quality tier this one doesn't have yet (`GEN-ERR-01`: not found, not an exception).
         */
        fun fromCode(code: Int): CloudDownloadQuality? = ALL.find { it.code == code }
    }
}
