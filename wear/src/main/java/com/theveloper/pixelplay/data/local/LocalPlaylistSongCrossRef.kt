package com.theveloper.pixelplay.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * Junction row recording that [songId] belongs to [playlistId] at [position]. Deliberately has
 * no foreign key to `local_songs`: a playlist syncs its full membership/order up front, before
 * the audio for every song has finished transferring (see `WearPlaylistSync`), so a cross-ref
 * routinely points at a songId that doesn't have a matching [LocalSongEntity] row yet.
 */
@Entity(
    tableName = "local_playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [
        Index(value = ["playlistId", "position"]),
        Index(value = ["songId"]),
    ],
)
data class LocalPlaylistSongCrossRef(
    val playlistId: String,
    val songId: String,
    val position: Int,
)
