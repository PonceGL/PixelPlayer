package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.model.TrackMetadata
import com.theveloper.pixelplay.data.sonos.protocol.XmlText
import com.theveloper.pixelplay.data.sonos.server.SonosMediaAccess
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Turns the app's songs into what is put in a speaker's queue, and back by the address of the song. */
internal object SonosTrackMapper {

    private const val DEFAULT_MIME = "audio/mpeg"
    private const val UNTITLED = "Unknown"
    private const val MAX_TEXT_CODE_POINTS = 256
    private const val ELLIPSIS = "…"
    private val SONG_PATH_PREFIX = listOf("sonos", "song")

    /** Null when no address can carry the song's id: the song cannot be played from a speaker and must be skipped. */
    fun urlsFor(entry: QueueEntry, access: SonosMediaAccess, baseUrl: String, revision: String): MediaUrls? {
        if (!access.servable(entry.songId)) return null
        return MediaUrls(
            song = access.songUrl(baseUrl, entry.songId, revision),
            art = if (entry.hasArt) access.artUrl(baseUrl, entry.songId, revision) else null,
        )
    }

    fun toTrack(entry: QueueEntry, urls: MediaUrls): TrackMetadata = TrackMetadata(
        title = limited(entry.title.takeIf { XmlText.escape(it).isNotBlank() } ?: UNTITLED),
        artist = entry.artist?.takeIf { it.isNotBlank() }?.let(::limited),
        album = entry.album?.takeIf { it.isNotBlank() }?.let(::limited),
        albumArtUrl = urls.art,
        durationMs = entry.durationMs.takeIf { it > 0 },
        mimeType = cleanMime(entry.mimeType),
        resourceUrl = urls.song,
    )

    /**
     * The id of the song that a queue item of ours points to, or null for anything else (Spotify, line-in, the
     * queue item of another phone running this app, ...). Ours means made with this server's token, which also
     * holds when the phone has a new address since the item was queued.
     */
    fun songIdOf(uri: String, access: SonosMediaAccess): String? {
        val url = uri.toHttpUrlOrNull() ?: return null
        if (!access.isOwnToken(url.queryParameter(SonosMediaAccess.TOKEN_PARAMETER))) return null
        val segments = url.pathSegments
        if (segments.size != SONG_PATH_PREFIX.size + 1 || segments.take(SONG_PATH_PREFIX.size) != SONG_PATH_PREFIX) return null
        return segments.last().takeIf { it.isNotEmpty() }
    }

    private fun cleanMime(mime: String?): String =
        mime?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MIME

    private fun limited(text: String): String {
        if (text.codePointCount(0, text.length) <= MAX_TEXT_CODE_POINTS) return text
        val end = text.offsetByCodePoints(0, MAX_TEXT_CODE_POINTS - 1)
        return text.substring(0, end) + ELLIPSIS
    }
}
