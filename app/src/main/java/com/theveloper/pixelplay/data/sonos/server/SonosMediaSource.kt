package com.theveloper.pixelplay.data.sonos.server

import java.io.InputStream

/** A song file as the media server serves it: the original bytes, never re-encoded. */
internal class AudioPayload(
    /** Size in bytes, or a negative number when it cannot be known. */
    val size: Long,
    val mimeType: String,
    val lastModifiedMs: Long?,
    /** Opens the file positioned at [offset]; the caller closes it. */
    val open: (offset: Long) -> InputStream,
)

internal class ArtPayload(
    val size: Long?,
    val mimeType: String,
    val lastModifiedMs: Long?,
    val open: () -> InputStream,
)

internal interface SonosMediaSource {
    /** The file of a published song, or null when it cannot be found. */
    suspend fun audio(songId: String): AudioPayload?

    /** The cover of a published song, or null when it cannot be found. */
    suspend fun art(songId: String): ArtPayload?
}
