package com.theveloper.pixelplay.data.download.model

/**
 * What a storage backend needs to place one download's file on disk: which item it belongs
 * to (used for the folder segment and the sanitized file name, `PLAN.md` §F1.5) and what
 * extension the file should carry.
 *
 * [extension] is **not** [CloudDownloadQuality.extension]: that field describes a requested
 * transcoding target and is `null` for [CloudDownloadQuality.MAX] (D-02, literal copy — there
 * is no target format to name ahead of time). This one is the *actual* extension of the
 * bytes about to be written, known only once the source resolves what the original file
 * really is (its container, from `Content-Type` or `MediaSources`), so it is supplied by the
 * caller at staging time, not derived from a static model.
 */
data class DownloadFileKey(
    val cloudDownloadKey: CloudDownloadKey,
    val extension: String,
)
