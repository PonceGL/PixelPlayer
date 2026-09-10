package com.theveloper.pixelplay.data.download.model

import com.theveloper.pixelplay.data.database.SourceType
import com.theveloper.pixelplay.data.model.Song

private const val JELLYFIN_CONTENT_URI_PREFIX = "jellyfin://"

/**
 * Identity of one cloud download: which source, which item on it. This is the shape of the
 * `cloud_downloads` table's primary key — never `songs.id` (a 32-bit hash that can collide)
 * and never `Song.jellyfinId` (populated by `SongEntity.toSong()` but left `null` by
 * `JellyfinSongEntity.toSong()` — verified against both files directly).
 *
 * The only way to build one is [cloudDownloadKey]: nobody else derives this key.
 */
data class CloudDownloadKey(
    val sourceType: Int,
    val remoteId: String,
) {
    /** The `cloud_downloads.id` primary key. */
    val storageId: String get() = "$sourceType:$remoteId"
}

/**
 * This song's [CloudDownloadKey], or `null` when it isn't from a source this build can
 * identify — a local file, or a scheme it doesn't recognize yet.
 *
 * Derived from [Song.contentUriString], which — unlike `Song.jellyfinId` — is populated
 * correctly on both paths that produce a [Song]: `SongEntity.toSong()` and
 * `JellyfinSongEntity.toSong()` both set it to `"jellyfin://<itemId>"`.
 *
 * One direction only: nothing reconstructs a [Song] from a [CloudDownloadKey]. If a caller
 * ever needs that, `cloud_downloads` already keeps `source_id` and `remote_id` as separate
 * columns — parsing them back out of [CloudDownloadKey.storageId] would break the moment a
 * `remoteId` contains a `:`.
 */
fun Song.cloudDownloadKey(): CloudDownloadKey? {
    if (!contentUriString.startsWith(JELLYFIN_CONTENT_URI_PREFIX)) return null
    val remoteId = contentUriString.removePrefix(JELLYFIN_CONTENT_URI_PREFIX)
    if (remoteId.isBlank()) return null
    return CloudDownloadKey(sourceType = SourceType.JELLYFIN, remoteId = remoteId)
}
