package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A standing "keep this collection downloaded" subscription — a playlist, album, artist or
 * library marked for automatic sync. The table is created up front so the schema is stable, but
 * nothing writes to it yet: the reconciler that populates and maintains these rows, and the DAO
 * to query them, are a later collections feature's job.
 */
@Entity(
    tableName = "cloud_download_subscriptions",
    indices = [
        Index(
            value = ["source_id", "collection_type", "remote_collection_id"],
            unique = true,
        ),
    ]
)
data class CloudDownloadSubscriptionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "source_id")
    val sourceId: Int,
    @ColumnInfo(name = "collection_type")
    val collectionType: String,
    @ColumnInfo(name = "remote_collection_id")
    val remoteCollectionId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "quality")
    val quality: Int,
    @ColumnInfo(name = "storage_backend")
    val storageBackend: String,
    @ColumnInfo(name = "storage_root")
    val storageRoot: String,
    @ColumnInfo(name = "auto_sync")
    val autoSync: Boolean = true,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "last_reconciled_at")
    val lastReconciledAt: Long? = null,
    @ColumnInfo(name = "last_reconcile_error")
    val lastReconcileError: String? = null,
)
