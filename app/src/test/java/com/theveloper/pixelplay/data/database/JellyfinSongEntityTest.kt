package com.theveloper.pixelplay.data.database

import com.theveloper.pixelplay.data.jellyfin.model.JellyfinSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JellyfinSongEntityTest {

    private fun song(size: Long? = null) = JellyfinSong(
        id = "item-1",
        title = "Song",
        artist = "Artist",
        album = "Album",
        duration = 180_000L,
        size = size,
    )

    @Test
    fun toEntity_carriesTheParsedSizeThrough() {
        val entity = song(size = 4_200_000L).toEntity(playlistId = "playlist-1")

        assertEquals(4_200_000L, entity.size)
    }

    @Test
    fun toEntity_leavesSizeNull_whenTheSourceDidNotReportOne() {
        val entity = song(size = null).toEntity(playlistId = "playlist-1")

        assertNull(entity.size)
    }
}
