package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per file that is, or was, being downloaded from a remote source for offline
 * playback — a literal copy of the source file, never a transcode.
 *
 * [id] is `CloudDownloadKey.storageId` (`"<sourceId>:<remoteId>"`, see
 * `com.theveloper.pixelplay.data.download.model.CloudDownloadKey`), which already makes
 * `(sourceId, remoteId)` unique by construction: a second unique index on those two columns
 * would enforce the exact same constraint the primary key already does, so this table
 * deliberately doesn't have one.
 *
 * Provenance is never touched here — nothing in `songs` changes when a row in this table is
 * created, updated or deleted. [songId] is a convenience for joins, never an identity column;
 * the real identity is `(sourceId, remoteId)`.
 */
@Entity(
    tableName = "cloud_downloads",
    indices = [
        Index(value = ["song_id"]),
        // No separate index on `state` alone: SQLite can already serve a state-only query from
        // this compound index's leftmost column, so a second one would just be a redundant
        // B-tree to maintain on every write to this table's busiest column.
        Index(value = ["state", "next_retry_at"]),
    ]
)
data class CloudDownloadEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "source_id")
    val sourceId: Int,
    @ColumnInfo(name = "remote_id")
    val remoteId: String,
    @ColumnInfo(name = "song_id")
    val songId: Long? = null,
    @ColumnInfo(name = "requested_quality")
    val requestedQuality: Int,
    @ColumnInfo(name = "quality")
    val quality: Int,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "storage_backend")
    val storageBackend: String,
    @ColumnInfo(name = "storage_root")
    val storageRoot: String,
    @ColumnInfo(name = "storage_ref")
    val storageRef: String? = null,
    @ColumnInfo(name = "staging_path")
    val stagingPath: String? = null,
    @ColumnInfo(name = "expected_bytes")
    val expectedBytes: Long? = null,
    @ColumnInfo(name = "downloaded_bytes")
    val downloadedBytes: Long = 0L,
    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long? = null,
    @ColumnInfo(name = "http_etag")
    val httpEtag: String? = null,
    @ColumnInfo(name = "http_last_modified")
    val httpLastModified: String? = null,
    @ColumnInfo(name = "container")
    val container: String? = null,
    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,
    @ColumnInfo(name = "has_embedded_art")
    val hasEmbeddedArt: Boolean? = null,
    @ColumnInfo(name = "error_code")
    val errorCode: String? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "next_retry_at")
    val nextRetryAt: Long? = null,
    @ColumnInfo(name = "priority")
    val priority: Int = 0,
    @ColumnInfo(name = "enqueued_at")
    val enqueuedAt: Long,
    @ColumnInfo(name = "started_at")
    val startedAt: Long? = null,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,
)
