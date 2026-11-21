package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.Relation

/**
 * Junction table for the many-to-many relationship between songs and genres, mirroring
 * [SongArtistCrossRef]. Enables multi-genre support where a song can belong to several
 * genres and a genre groups songs from across the whole library.
 */
@Entity(
    tableName = "song_genre_cross_ref",
    primaryKeys = ["song_id", "genre_id"],
    indices = [
        Index(value = ["song_id"]),
        Index(value = ["genre_id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["song_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GenreEntity::class,
            parentColumns = ["id"],
            childColumns = ["genre_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SongGenreCrossRef(
    @ColumnInfo(name = "song_id") val songId: Long,
    @ColumnInfo(name = "genre_id") val genreId: Long
)

/** A song along with every genre it belongs to. */
data class SongWithGenres(
    @Embedded val song: SongEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = SongGenreCrossRef::class,
            parentColumn = "song_id",
            entityColumn = "genre_id"
        )
    )
    val genres: List<GenreEntity>
)

/** A genre along with every song that belongs to it. */
data class GenreWithSongs(
    @Embedded val genre: GenreEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = SongGenreCrossRef::class,
            parentColumn = "genre_id",
            entityColumn = "song_id"
        )
    )
    val songs: List<SongEntity>
)
