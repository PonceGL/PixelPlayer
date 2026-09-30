package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A persisted, deduplicated genre. [id] is deterministic (see genreIdFromMatchKey in
 * GenreParsingUtils) so the same genre always resolves to the same row across sync runs
 * without needing a lookup query first. [matchKey] is the real identity (case/diacritic
 * -insensitive, alias-resolved); [name] is the display string as first encountered in a tag.
 */
@Entity(
    tableName = "genres",
    indices = [Index(value = ["match_key"], unique = true)]
)
data class GenreEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "match_key") val matchKey: String
)
