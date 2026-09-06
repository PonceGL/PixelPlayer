package com.theveloper.pixelplay.data.download.model

import com.theveloper.pixelplay.data.database.JellyfinSongEntity
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.database.SourceType
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CloudDownloadKeyTest {

    private fun localSongEntity(contentUriString: String = "content://media/external/audio/1") =
        SongEntity(
            id = 1L,
            title = "Track",
            artistName = "Artist",
            artistId = 1L,
            albumName = "Album",
            albumId = 1L,
            contentUriString = contentUriString,
            albumArtUriString = null,
            duration = 1000L,
            genre = null,
            filePath = "/storage/emulated/0/Music/track.mp3",
            parentDirectoryPath = "/storage/emulated/0/Music"
        )

    private fun jellyfinSongEntity(jellyfinId: String) = JellyfinSongEntity(
        id = "playlist-1_$jellyfinId",
        jellyfinId = jellyfinId,
        playlistId = "playlist-1",
        title = "Track",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = null,
        duration = 1000L,
        trackNumber = 1,
        discNumber = 1,
        year = 2024,
        genre = null,
        bitRate = null,
        mimeType = null,
        path = "",
        dateAdded = 0L
    )

    // ─── Both paths that produce a Song agree on the key ────────────────────────

    @Test
    fun `cloudDownloadKey from the main library path matches the Jellyfin playlist path`() {
        val itemId = "abc123-guid"

        val fromLibraryPath = localSongEntity(contentUriString = "jellyfin://$itemId").toSong()
        val fromPlaylistPath = jellyfinSongEntity(itemId).toSong()

        val keyFromLibrary = fromLibraryPath.cloudDownloadKey()
        val keyFromPlaylist = fromPlaylistPath.cloudDownloadKey()

        assertEquals(CloudDownloadKey(SourceType.JELLYFIN, itemId), keyFromLibrary)
        assertEquals(keyFromLibrary, keyFromPlaylist)
    }

    @Test
    fun `the Jellyfin playlist path leaves Song jellyfinId null but cloudDownloadKey still works`() {
        // Regression guard for the exact bug §4.6 documents: Song.jellyfinId is NOT a safe
        // source of the key because JellyfinSongEntity.toSong() never populates it.
        val song = jellyfinSongEntity("abc123-guid").toSong()

        assertNull(song.jellyfinId)
        assertEquals(CloudDownloadKey(SourceType.JELLYFIN, "abc123-guid"), song.cloudDownloadKey())
    }

    // ─── Edge cases from F1.md §5 · F1.0 ─────────────────────────────────────────

    @Test
    fun `a local song has no cloudDownloadKey`() {
        val song = localSongEntity(contentUriString = "content://media/external/audio/1").toSong()

        assertNull(song.cloudDownloadKey())
    }

    @Test
    fun `an unrecognized scheme has no cloudDownloadKey`() {
        val song = localSongEntity(contentUriString = "netease://12345").toSong()

        assertNull(song.cloudDownloadKey())
    }

    @Test
    fun `a jellyfin uri with an empty remote id has no cloudDownloadKey`() {
        val song = localSongEntity(contentUriString = "jellyfin://").toSong()

        assertNull(song.cloudDownloadKey())
    }

    @Test
    fun `a remoteId containing a colon still round-trips through storageId derivation`() {
        // The conversion is one-way on purpose (§4.6): storageId is not meant to be parsed
        // back apart, but deriving it must not crash or silently truncate the id.
        val key = CloudDownloadKey(sourceType = SourceType.JELLYFIN, remoteId = "guid:with:colons")

        assertEquals("6:guid:with:colons", key.storageId)
    }

    // ─── SourceType coverage (R15: not an enum, no compiler exhaustiveness) ──────

    @Test
    fun `every SourceType constant is a distinct value cloudDownloadKey could carry`() {
        val allSourceTypeConstants = listOf(
            SourceType.LOCAL,
            SourceType.TELEGRAM,
            SourceType.NETEASE,
            SourceType.GDRIVE,
            SourceType.QQMUSIC,
            SourceType.NAVIDROME,
            SourceType.JELLYFIN,
        )

        assertEquals(7, allSourceTypeConstants.distinct().size)
        assertEquals(SourceType.JELLYFIN, CloudDownloadKey(SourceType.JELLYFIN, "x").sourceType)
    }

    // ─── storageId stability ──────────────────────────────────────────────────────

    @Test
    fun `storageId is stable for the same inputs`() {
        val first = CloudDownloadKey(SourceType.JELLYFIN, "same-id").storageId
        val second = CloudDownloadKey(SourceType.JELLYFIN, "same-id").storageId

        assertEquals(first, second)
    }

    @Test
    fun `storageId differs when either input differs`() {
        val base = CloudDownloadKey(SourceType.JELLYFIN, "id-1")

        assertEquals(true, base.storageId != CloudDownloadKey(SourceType.JELLYFIN, "id-2").storageId)
        assertEquals(true, base.storageId != CloudDownloadKey(SourceType.NAVIDROME, "id-1").storageId)
    }
}
