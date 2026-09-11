package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Deliberately minimal: enough to prove the schema and the one-row-per-remote-item invariant.
 * Querying by state, updating progress and priority, and the rest of what the download engine
 * (`com.theveloper.pixelplay.data.download.engine`) needs to actually run a queue belong to
 * that engine work, which extends this interface rather than duplicating it.
 *
 * `cloud_download_subscriptions` and `cloud_download_refs` have no DAO yet, on purpose: those
 * tables are created up front so the schema is stable, but their query surface belongs to the
 * collections feature that will populate them.
 */
@Dao
interface CloudDownloadDao {

    /**
     * Enqueues a download. Never [OnConflictStrategy.REPLACE]: replacing a `COMPLETED` row
     * would reset it to whatever [entity] carries and leave its published file on disk with no
     * row pointing at it. A second enqueue of the same `(sourceId, remoteId)` is silently
     * ignored — `id` already encodes that pair, so the primary key enforces the uniqueness
     * invariant without a second index.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: CloudDownloadEntity): Long

    @Query("SELECT * FROM cloud_downloads WHERE id = :id")
    suspend fun getById(id: String): CloudDownloadEntity?

    @Query("SELECT * FROM cloud_downloads")
    suspend fun getAll(): List<CloudDownloadEntity>

    @Query("DELETE FROM cloud_downloads WHERE id = :id")
    suspend fun deleteById(id: String)
}
