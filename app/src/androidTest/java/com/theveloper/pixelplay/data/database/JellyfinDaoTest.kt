package com.theveloper.pixelplay.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class JellyfinDaoTest {

    private lateinit var dao: JellyfinDao
    private lateinit var db: PixelPlayDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PixelPlayDatabase::class.java)
            .addCallback(PixelPlayDatabase.createRuntimeArtifactsCallback())
            .allowMainThreadQueries()
            .build()
        dao = db.jellyfinDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    private fun song(
        rowId: String,
        playlistId: String,
        jellyfinId: String = "item-1",
        size: Long? = null,
    ) = JellyfinSongEntity(
        id = rowId,
        jellyfinId = jellyfinId,
        playlistId = playlistId,
        title = "Song",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = null,
        duration = 180_000L,
        trackNumber = 1,
        discNumber = 1,
        year = 2024,
        genre = null,
        bitRate = null,
        mimeType = null,
        path = "/jellyfin/$jellyfinId",
        dateAdded = 1_000L,
        size = size,
    )

    @Test
    fun getKnownSongSize_returnsNull_whenNoRowMatches() = runTest {
        assertNull(dao.getKnownSongSize("does-not-exist"))
    }

    @Test
    fun getKnownSongSize_returnsNull_whenTheOnlyRowHasNoSizeYet() = runTest {
        dao.insertSong(song(rowId = "library_item-1", playlistId = "__library__", size = null))

        assertNull(dao.getKnownSongSize("item-1"))
    }

    /**
     * The same Jellyfin item can have a row per playlist it belongs to, plus a library row —
     * not every one of them is guaranteed to have been synced since size started being
     * persisted. A caller asking "do we know this item's size" must get the real value even if
     * the first row SQLite happens to return is one still sitting at `NULL`.
     */
    @Test
    fun getKnownSongSize_returnsTheKnownValue_evenWhenAnotherRowForTheSameItemIsStillNull() = runTest {
        dao.insertSong(song(rowId = "library_item-1", playlistId = "__library__", size = null))
        dao.insertSong(song(rowId = "playlist-1_item-1", playlistId = "playlist-1", size = 4_200_000L))

        assertEquals(4_200_000L, dao.getKnownSongSize("item-1"))
    }
}
