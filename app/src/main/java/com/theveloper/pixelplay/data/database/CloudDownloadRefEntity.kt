package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Links a [CloudDownloadSubscriptionEntity] to a `cloud_downloads` row it keeps downloaded.
 * The same download can be referenced by more than one subscription (a song in two downloaded
 * playlists survives either one being removed) — refcounting is a `COUNT` over this table,
 * owned by `F4`, not a column anywhere.
 *
 * Deleting a subscription cascades here (`ON DELETE CASCADE`) — a ref with no subscription is
 * meaningless. Deleting a `cloud_downloads` row does **not** cascade the other way: there is no
 * foreign key from [downloadId] to `cloud_downloads.id`, on purpose. `F4`'s reconciler owns
 * cleaning up refs to a download that no longer exists; a hard FK here would turn that into a
 * crash instead of a reconciliation.
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
