package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Links a [CloudDownloadSubscriptionEntity] to a `cloud_downloads` row it keeps downloaded.
 * The same download can be referenced by more than one subscription (a song in two downloaded
 * playlists survives either one being removed) — refcounting is a `COUNT` over this table, not
 * a column anywhere. No code populates this table yet; a future collections feature's
 * reconciler owns that.
 *
 * `ON DELETE CASCADE` here is **declared, not enforced**: SQLite disables per-connection
 * foreign-key checking by default, and nothing in this app's `RoomDatabase` setup turns it on —
 * confirmed by grepping the whole database module for `PRAGMA foreign_keys`/
 * `setForeignKeyConstraintsEnabled`, and by checking the exact pinned Room version's own
 * sources: neither ever issues that PRAGMA on its own. Turning it on globally isn't done here
 * on purpose — this schema isn't the only place a foreign key already exists (`songs.album_id`/
 * `songs.artist_id`, `song_artist_cross_ref`), and enabling enforcement app-wide would newly
 * start throwing on any write path that currently relies, even by accident, on it being
 * unenforced. That is a dedicated task of its own, not a side effect of adding this table.
 * Until then, whatever deletes a [CloudDownloadSubscriptionEntity] must delete its refs itself,
 * in the same transaction — the declared cascade is a statement of intent for the day this
 * does get enabled, not something a caller can rely on today.
 *
 * Deleting a `cloud_downloads` row does **not** cascade the other way, enforced or not: there
 * is no foreign key from [downloadId] to `cloud_downloads.id`, on purpose. Cleaning up refs to
 * a download that no longer exists is a reconciliation job; a hard FK here would turn that into
 * a crash instead.
 */
@Entity(
    tableName = "cloud_download_refs",
    primaryKeys = ["subscription_id", "download_id"],
    indices = [
        Index(value = ["download_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = CloudDownloadSubscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["subscription_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ]
)
data class CloudDownloadRefEntity(
    @ColumnInfo(name = "subscription_id")
    val subscriptionId: String,
    @ColumnInfo(name = "download_id")
    val downloadId: String,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
)
