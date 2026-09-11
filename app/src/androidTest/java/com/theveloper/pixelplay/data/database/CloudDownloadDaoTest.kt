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
class CloudDownloadDaoTest {

    private lateinit var dao: CloudDownloadDao
    private lateinit var db: PixelPlayDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PixelPlayDatabase::class.java)
            .addCallback(PixelPlayDatabase.createRuntimeArtifactsCallback())
            .allowMainThreadQueries()
            .build()
        dao = db.cloudDownloadDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    private fun pendingDownload(
        id: String = "6:item-1",
        sourceId: Int = 6,
        remoteId: String = "item-1",
    ) = CloudDownloadEntity(
        id = id,
        sourceId = sourceId,
        remoteId = remoteId,
        requestedQuality = 0,
        quality = 0,
        state = "PENDING",
        storageBackend = "APP_PRIVATE",
        storageRoot = "/data/downloads",
        enqueuedAt = 1_000L,
    )

    @Test
    fun insert_thenGetById_returnsTheRow() = runTest {
        dao.insert(pendingDownload())

        val row = dao.getById("6:item-1")

        assertEquals("item-1", row?.remoteId)
        assertEquals("PENDING", row?.state)
    }

    @Test
    fun getById_returnsNull_whenNoRowMatches() = runTest {
        val row = dao.getById("does-not-exist")

        assertNull(row)
    }

    /**
     * One file per (sourceId, remoteId), never two rows for the same remote item — even if
     * something tries to enqueue it twice (a duplicate tap, a retried collection sync). `id`
     * already encodes that pair, so the primary key is the enforcement mechanism; this proves
     * the DAO's conflict strategy respects it instead of quietly working around it.
     */
    @Test
    fun insert_sameSourceAndRemoteIdTwice_keepsOnlyTheFirstRow() = runTest {
        dao.insert(pendingDownload())
        dao.insert(
            pendingDownload().copy(
                state = "RUNNING",
                downloadedBytes = 500L,
            )
        )

        val rows = dao.getAll()

        assertEquals(1, rows.size)
        // The second insert was ignored: the first row's state was never overwritten. A
        // REPLACE conflict strategy would have let "RUNNING" win here — replacing an
        // in-flight or completed row like that would reset its state and orphan whatever file
        // it already pointed at, which is exactly why this DAO never uses REPLACE.
        assertEquals("PENDING", rows.single().state)
    }

    @Test
    fun deleteById_removesTheRow() = runTest {
        dao.insert(pendingDownload())

        dao.deleteById("6:item-1")

        assertNull(dao.getById("6:item-1"))
    }
}
