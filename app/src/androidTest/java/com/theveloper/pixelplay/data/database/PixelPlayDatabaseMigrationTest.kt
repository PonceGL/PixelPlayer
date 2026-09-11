package com.theveloper.pixelplay.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PixelPlayDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PixelPlayDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (version in 25..42) {
            context.deleteDatabase(databaseNameFor(version))
        }
        context.deleteDatabase(DB_NAME_33_TO_34)
        context.deleteDatabase(DB_NAME_23_TO_24_DRIFTED)
        context.deleteDatabase(DB_NAME_35_TO_36)
        context.deleteDatabase(DB_NAME_39_TO_40)
        context.deleteDatabase(DB_NAME_42_TO_43)
        context.deleteDatabase(DB_NAME_42_TO_43_DATA)
        context.deleteDatabase(DB_NAME_42_TO_43_IDEMPOTENT)
        context.deleteDatabase(DB_NAME_42_TO_43_CASCADE)
    }

    @Test
    fun migrateEveryExportedSchemaToLatest() {
        for (startVersion in 25..42) {
            helper.createDatabase(databaseNameFor(startVersion), startVersion).close()

            helper.runMigrationsAndValidate(
                databaseNameFor(startVersion),
                PixelPlayDatabaseVersion.LATEST,
                true,
                *ALL_MIGRATIONS
            ).close()
        }
    }

    @Test
    fun migration33To34AddsArtistsJsonColumnToSongs() {
        helper.createDatabase(DB_NAME_33_TO_34, 33).close()

        helper.runMigrationsAndValidate(
            DB_NAME_33_TO_34,
            34,
            true,
            PixelPlayDatabase.MIGRATION_33_34
        ).let { db ->
            val cursor = db.query("PRAGMA table_info(`songs`)")
            try {
                val nameIndex = cursor.getColumnIndex("name")
                val defaultValueIndex = cursor.getColumnIndex("dflt_value")
                var foundArtistsJson = false
                var defaultValue: String? = null

                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "artists_json") {
                        foundArtistsJson = true
                        defaultValue = cursor.getString(defaultValueIndex)
                        break
                    }
                }

                assertTrue(foundArtistsJson)
                assertEquals("NULL", defaultValue)
            } finally {
                cursor.close()
                db.close()
            }
        }
    }

    @Test
    fun migration23To24RepairsSongsWithoutDateAddedBeforeCreatingIndexes() {
        val openHelper = createDriftedVersion23Database(DB_NAME_23_TO_24_DRIFTED)
        val db = openHelper.writableDatabase

        try {
            PixelPlayDatabase.MIGRATION_23_24.migrate(db)

            val columns = db.tableColumns("songs")
            assertTrue("date_added" in columns)

            db.query("SELECT date_added FROM songs WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }

            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_songs_date_added'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("index_songs_date_added", cursor.getString(0))
            }
        } finally {
            db.close()
            openHelper.close()
        }
    }

    @Test
    fun migration35To36AddsSongsFtsTable() {
        helper.createDatabase(DB_NAME_35_TO_36, 35).close()

        helper.runMigrationsAndValidate(
            DB_NAME_35_TO_36,
            36,
            true,
            PixelPlayDatabase.MIGRATION_35_36
        ).let { db ->
            val cursor = db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'songs_fts'")
            try {
                assertTrue(cursor.moveToFirst())
                assertEquals("songs_fts", cursor.getString(0))
            } finally {
                cursor.close()
                db.close()
            }
        }
    }

    @Test
    fun migration39To40AddsCompositeSongIndexes() {
        helper.createDatabase(DB_NAME_39_TO_40, 39).close()

        helper.runMigrationsAndValidate(
            DB_NAME_39_TO_40,
            40,
            true,
            PixelPlayDatabase.MIGRATION_39_40
        ).let { db ->
            try {
                val indexes = db.tableIndexes("songs")
                assertTrue("index_songs_parent_directory_path_source_type_album_id" in indexes)
                assertTrue("index_songs_parent_directory_path_source_type_id" in indexes)
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun migration42To43AddsNullableSizeColumnAndCloudDownloadTables() {
        helper.createDatabase(DB_NAME_42_TO_43, 42).close()

        helper.runMigrationsAndValidate(
            DB_NAME_42_TO_43,
            43,
            true,
            PixelPlayDatabase.MIGRATION_42_43
        ).let { db ->
            try {
                assertTrue("size" in db.tableColumns("jellyfin_songs"))

                for (table in listOf(
                    "cloud_downloads",
                    "cloud_download_subscriptions",
                    "cloud_download_refs"
                )) {
                    db.query(
                        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                        arrayOf(table)
                    ).use { cursor ->
                        assertTrue("expected table `$table` to exist", cursor.moveToFirst())
                    }
                }
            } finally {
                db.close()
            }
        }
    }

    /**
     * The generic [migrateEveryExportedSchemaToLatest] proves the schema shape; this proves the
     * actual data-preservation contract: a row that existed before the migration keeps its data
     * and gets `NULL` for the new column, not an error and not a default value someone forgot
     * they picked.
     */
    @Test
    fun migration42To43LeavesSizeNullOnAPreExistingJellyfinSongsRow() {
        val db = helper.createDatabase(DB_NAME_42_TO_43_DATA, 42)
        try {
            db.execSQL(
                """
                    INSERT INTO jellyfin_songs (
                        id, jellyfin_id, playlist_id, title, artist, artist_id, album, album_id,
                        duration, track_number, disc_number, year, genre, bitRate, mime_type,
                        path, date_added
                    ) VALUES (
                        'row-1', 'item-1', 'playlist-1', 'Song', 'Artist', NULL, 'Album', NULL,
                        180000, 1, 1, 2024, NULL, NULL, NULL, '/jellyfin/item-1', 1234567890
                    )
                """.trimIndent()
            )

            PixelPlayDatabase.MIGRATION_42_43.migrate(db)

            db.query("SELECT title, size FROM jellyfin_songs WHERE id = 'row-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Song", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migration42To43IsIdempotent() {
        val db = helper.createDatabase(DB_NAME_42_TO_43_IDEMPOTENT, 42)
        try {
            PixelPlayDatabase.MIGRATION_42_43.migrate(db)
            // A second application must not throw (a naive `ALTER TABLE ... ADD COLUMN`
            // without the `getTableColumns()` guard fails here with "duplicate column name") —
            // this call not throwing is the assertion.
            PixelPlayDatabase.MIGRATION_42_43.migrate(db)

            assertTrue("size" in db.tableColumns("jellyfin_songs"))
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'cloud_downloads'"
            ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        } finally {
            db.close()
        }
    }

    /**
     * Proves the schema's *declared* cascade shape, not current production behavior: the app's
     * own `RoomDatabase` never enables per-connection foreign-key enforcement (see
     * [CloudDownloadRefEntity]'s KDoc for why that's not done lightly), so this test forces it
     * on for its own connection with the `PRAGMA` below. Until something enables it for real,
     * a caller that deletes a subscription must delete its refs itself — this test documents
     * the schema's intent, not a guarantee today's app code can rely on.
     */
    @Test
    fun migration42To43DeletingSubscriptionCascadesRefsButNotDownloads() {
        val db = helper.createDatabase(DB_NAME_42_TO_43_CASCADE, 42)
        try {
            PixelPlayDatabase.MIGRATION_42_43.migrate(db)
            db.execSQL("PRAGMA foreign_keys = ON")

            db.execSQL(
                """
                    INSERT INTO cloud_downloads (
                        id, source_id, remote_id, requested_quality, quality, state,
                        storage_backend, storage_root, downloaded_bytes, attempt_count,
                        priority, enqueued_at
                    ) VALUES (
                        '6:item-1', 6, 'item-1', 0, 0, 'PENDING', 'APP_PRIVATE',
                        '/data/downloads', 0, 0, 0, 1234567890
                    )
                """.trimIndent()
            )
            db.execSQL(
                """
                    INSERT INTO cloud_download_subscriptions (
                        id, source_id, collection_type, remote_collection_id, display_name,
                        quality, storage_backend, storage_root, auto_sync, state, created_at
                    ) VALUES (
                        'jellyfin:playlist:p1', 6, 'PLAYLIST', 'p1', 'My playlist', 0,
                        'APP_PRIVATE', '/data/downloads', 1, 'ACTIVE', 1234567890
                    )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO cloud_download_refs (subscription_id, download_id, added_at) " +
                    "VALUES ('jellyfin:playlist:p1', '6:item-1', 1234567890)"
            )

            db.query("SELECT COUNT(*) FROM cloud_download_refs").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }

            db.execSQL("DELETE FROM cloud_download_subscriptions WHERE id = 'jellyfin:playlist:p1'")

            // The ref cascades away with its subscription...
            db.query("SELECT COUNT(*) FROM cloud_download_refs").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            // ...but the download row itself is untouched: there is no FK the other way, on
            // purpose — cleaning up a download nothing references anymore is a reconciliation
            // job for whichever future feature manages collections, not something a hard FK
            // here should turn into a crash.
            db.query("SELECT COUNT(*) FROM cloud_downloads WHERE id = '6:item-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    private fun databaseNameFor(startVersion: Int): String = "migration-test-$startVersion"

    private fun createDriftedVersion23Database(
        databaseName: String
    ): SupportSQLiteOpenHelper {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)

        val callback = object : SupportSQLiteOpenHelper.Callback(23) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                        CREATE TABLE IF NOT EXISTS songs (
                            id INTEGER NOT NULL PRIMARY KEY,
                            title TEXT NOT NULL,
                            artist_name TEXT NOT NULL,
                            artist_id INTEGER NOT NULL,
                            album_artist TEXT,
                            album_name TEXT NOT NULL,
                            album_id INTEGER NOT NULL,
                            content_uri_string TEXT NOT NULL,
                            album_art_uri_string TEXT,
                            duration INTEGER NOT NULL,
                            genre TEXT,
                            file_path TEXT NOT NULL,
                            parent_directory_path TEXT NOT NULL,
                            is_favorite INTEGER NOT NULL DEFAULT 0,
                            lyrics TEXT DEFAULT null,
                            track_number INTEGER NOT NULL DEFAULT 0,
                            year INTEGER NOT NULL DEFAULT 0,
                            mime_type TEXT,
                            bitrate INTEGER,
                            sample_rate INTEGER,
                            telegram_chat_id INTEGER,
                            telegram_file_id INTEGER
                        )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                        INSERT INTO songs (
                            id,
                            title,
                            artist_name,
                            artist_id,
                            album_artist,
                            album_name,
                            album_id,
                            content_uri_string,
                            album_art_uri_string,
                            duration,
                            genre,
                            file_path,
                            parent_directory_path,
                            is_favorite,
                            lyrics,
                            track_number,
                            year,
                            mime_type,
                            bitrate,
                            sample_rate,
                            telegram_chat_id,
                            telegram_file_id
                        ) VALUES (
                            1,
                            'Song',
                            'Artist',
                            10,
                            NULL,
                            'Album',
                            20,
                            'content://song/1',
                            NULL,
                            180000,
                            NULL,
                            '/music/song.mp3',
                            '/music',
                            0,
                            NULL,
                            1,
                            2024,
                            'audio/mpeg',
                            320000,
                            44100,
                            NULL,
                            NULL
                        )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                        CREATE TABLE IF NOT EXISTS favorites (
                            songId INTEGER NOT NULL PRIMARY KEY,
                            isFavorite INTEGER NOT NULL,
                            timestamp INTEGER NOT NULL
                        )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                        CREATE TABLE IF NOT EXISTS song_engagements (
                            song_id TEXT NOT NULL PRIMARY KEY,
                            play_count INTEGER NOT NULL DEFAULT 0,
                            total_play_duration_ms INTEGER NOT NULL DEFAULT 0,
                            last_played_timestamp INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
            ) = Unit
        }

        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(callback)
                .build()
        )
    }

    private fun SupportSQLiteDatabase.tableColumns(tableName: String): Set<String> {
        val columns = mutableSetOf<String>()
        query("PRAGMA table_info(`$tableName`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
        }
        return columns
    }

    private fun SupportSQLiteDatabase.tableIndexes(tableName: String): Set<String> {
        val indexes = mutableSetOf<String>()
        query("PRAGMA index_list(`$tableName`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                indexes += cursor.getString(nameIndex)
            }
        }
        return indexes
    }

    private object PixelPlayDatabaseVersion {
        const val LATEST = 43
    }

    companion object {
        private const val DB_NAME_23_TO_24_DRIFTED = "migration-test-23-to-24-drifted"
        private const val DB_NAME_33_TO_34 = "migration-test-33-to-34"
        private const val DB_NAME_35_TO_36 = "migration-test-35-to-36"
        private const val DB_NAME_39_TO_40 = "migration-test-39-to-40"
        private const val DB_NAME_42_TO_43 = "migration-test-42-to-43"
        private const val DB_NAME_42_TO_43_DATA = "migration-test-42-to-43-data"
        private const val DB_NAME_42_TO_43_IDEMPOTENT = "migration-test-42-to-43-idempotent"
        private const val DB_NAME_42_TO_43_CASCADE = "migration-test-42-to-43-cascade"

        private val ALL_MIGRATIONS = arrayOf(
            PixelPlayDatabase.MIGRATION_25_26,
            PixelPlayDatabase.MIGRATION_26_27,
            PixelPlayDatabase.MIGRATION_27_28,
            PixelPlayDatabase.MIGRATION_28_29,
            PixelPlayDatabase.MIGRATION_29_30,
            PixelPlayDatabase.MIGRATION_30_31,
            PixelPlayDatabase.MIGRATION_31_32,
            PixelPlayDatabase.MIGRATION_32_33,
            PixelPlayDatabase.MIGRATION_33_34,
            PixelPlayDatabase.MIGRATION_34_35,
            PixelPlayDatabase.MIGRATION_35_36,
            PixelPlayDatabase.MIGRATION_36_37,
            PixelPlayDatabase.MIGRATION_37_38,
            PixelPlayDatabase.MIGRATION_38_39,
            PixelPlayDatabase.MIGRATION_39_40,
            PixelPlayDatabase.MIGRATION_40_41,
            PixelPlayDatabase.MIGRATION_41_42,
            PixelPlayDatabase.MIGRATION_42_43
        )
    }
}
