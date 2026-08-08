package com.theveloper.pixelplay.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies [WearMusicDatabase.MIGRATION_5_6] against a hand-built v5 database file.
 *
 * `:wear` doesn't export Room schema JSON (`exportSchema = false`), so [androidx.room.testing.MigrationTestHelper]
 * — which needs those fixtures — isn't available here. Instead this builds a real on-disk SQLite
 * file matching the v5 `local_songs` shape (see [LocalSongEntity]), then opens it through Room
 * with the migration attached, the same way a real upgrading device would.
 */
@RunWith(AndroidJUnit4::class)
class WearMusicDatabaseMigrationTest {

    private val dbName = "migration-test-wear-music.db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanup() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate5To6_createsPlaylistTablesAndPreservesExistingSongs() = runTest {
        seedVersion5Database()

        val migratedDb = Room.databaseBuilder(context, WearMusicDatabase::class.java, dbName)
            .addMigrations(WearMusicDatabase.MIGRATION_5_6)
            .build()

        try {
            val song = migratedDb.localSongDao().getSongById("song-1")
            assertThat(song).isNotNull()
            assertThat(song?.title).isEqualTo("Existing song")

            // The playlist tables must exist and be queryable — this throws if the migration
            // didn't run (or ran with malformed SQL) rather than returning a false "empty" result.
            val playlists = migratedDb.openHelper.readableDatabase.query("SELECT * FROM local_playlists")
            playlists.use { assertThat(it.count).isEqualTo(0) }

            val playlistSongs = migratedDb.openHelper.readableDatabase.query("SELECT * FROM local_playlist_songs")
            playlistSongs.use { assertThat(it.count).isEqualTo(0) }
        } finally {
            migratedDb.close()
        }
    }

    /** Hand-writes a v5 database file: the `local_songs` shape frozen right before this migration. */
    private fun seedVersion5Database() {
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL(
            "CREATE TABLE local_songs (" +
                "songId TEXT NOT NULL PRIMARY KEY, " +
                "title TEXT NOT NULL, " +
                "artist TEXT NOT NULL, " +
                "album TEXT NOT NULL, " +
                "albumId INTEGER NOT NULL, " +
                "duration INTEGER NOT NULL, " +
                "mimeType TEXT NOT NULL, " +
                "fileSize INTEGER NOT NULL, " +
                "bitrate INTEGER NOT NULL, " +
                "sampleRate INTEGER NOT NULL, " +
                "isFavorite INTEGER NOT NULL, " +
                "favoriteSyncPending INTEGER NOT NULL, " +
                "paletteSeedArgb INTEGER, " +
                "themePaletteJson TEXT, " +
                "artworkPath TEXT, " +
                "localPath TEXT NOT NULL, " +
                "transferredAt INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO local_songs (songId, title, artist, album, albumId, duration, mimeType, " +
                "fileSize, bitrate, sampleRate, isFavorite, favoriteSyncPending, paletteSeedArgb, " +
                "themePaletteJson, artworkPath, localPath, transferredAt) VALUES " +
                "('song-1', 'Existing song', 'Artist', 'Album', 1, 180000, 'audio/mp4', 4000000, " +
                "128000, 44100, 0, 0, NULL, NULL, NULL, '/music/song-1.m4a', 1000)"
        )
        db.version = 5
        db.close()
    }
}
