package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

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

    /** Whole-row read-modify-write. The engine always reads a row before changing it, so a
     * matching set of narrow `UPDATE ... SET x = :x` methods would only be more surface to
     * keep in sync, never more correct. */
    @Update
    suspend fun update(entity: CloudDownloadEntity)

    @Query("SELECT * FROM cloud_downloads WHERE state = :state ORDER BY priority DESC, enqueued_at ASC")
    suspend fun getRowsInStateByPriority(state: String): List<CloudDownloadEntity>

    @Query("SELECT * FROM cloud_downloads WHERE state IN (:states)")
    suspend fun getRowsInStates(states: List<String>): List<CloudDownloadEntity>

    @Query("SELECT storage_ref FROM cloud_downloads WHERE storage_ref IS NOT NULL")
    suspend fun getAllPublishedRefs(): List<String>

    @Query("SELECT staging_path FROM cloud_downloads WHERE staging_path IS NOT NULL AND state IN (:states)")
    suspend fun getStagingPathsForStates(states: List<String>): List<String>
}
