package com.theveloper.pixelplay.data.sonos.model

/** What a Sonos speaker (and its apps) show about a track in its queue. */
internal data class TrackMetadata(
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtUrl: String?,
    val durationMs: Long?,
    val mimeType: String,
    val resourceUrl: String,
) {
    // The addresses carry the access token of the media server.
    override fun toString(): String = "TrackMetadata(title=$title)"
}
