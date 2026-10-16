package com.theveloper.pixelplay.data.sonos.session

/** One song of the app's queue, as much of it as a speaker needs. */
internal data class QueueEntry(
    val songId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val mimeType: String?,
    val hasArt: Boolean,
)

/** Where a speaker fetches a song and its cover from. The addresses carry the access token, so they are never printed. */
internal data class MediaUrls(val song: String, val art: String?) {
    override fun toString(): String = "MediaUrls(art=${art != null})"
}
