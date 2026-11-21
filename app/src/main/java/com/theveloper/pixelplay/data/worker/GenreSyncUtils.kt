package com.theveloper.pixelplay.data.worker

import com.theveloper.pixelplay.data.database.GenreEntity
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.database.SongGenreCrossRef

internal data class GenreProcessResult(
    val genres: List<GenreEntity>,
    val crossRefs: List<SongGenreCrossRef>
)

/**
 * Resolves and deduplicates genres for a batch of songs, mirroring the artist-processing
 * loop in SyncWorker but kept as a standalone, dependency-free function - unlike its artist
 * counterpart (a private method on the DI-heavy SyncWorker class), this needs nothing but
 * plain data, so it is directly unit-testable (see GenreSyncUtilsTest) without an
 * instrumented test harness.
 *
 * Genre ids are deterministic (see genreIdFromMatchKey), so unlike artist processing this
 * needs no running id counter: [existingGenreNames] only supplies the already-established
 * display name for a genre so a differently-cased/accented occurrence in this batch doesn't
 * overwrite it.
 */
internal fun preProcessAndDeduplicateGenres(
    songs: List<SongEntity>,
    genreDelimiters: List<String>,
    wordDelimiters: List<String> = emptyList(),
    existingGenreNames: Map<Long, String> = emptyMap(),
    genresFromTagBySongId: Map<Long, List<String>> = emptyMap()
): GenreProcessResult {
    val genreNameById = existingGenreNames.toMutableMap()
    val crossRefs = mutableListOf<SongGenreCrossRef>()

    songs.forEach { song ->
        val rawGenreName = song.genre?.takeIf { it.isNotBlank() } ?: return@forEach
        val genresFromTag = genresFromTagBySongId[song.id] ?: emptyList()
        val genreNames = resolveGenresForSong(
            genresFromTag = genresFromTag,
            rawGenreName = rawGenreName,
            genreDelimiters = genreDelimiters,
            wordDelimiters = wordDelimiters
        )

        val idsSeenForSong = mutableSetOf<Long>()
        genreNames.forEach { genreName ->
            val trimmed = genreName.trim()
            if (trimmed.isEmpty()) return@forEach

            val id = genreIdFromMatchKey(trimmed.genreMatchKey())
            genreNameById.putIfAbsent(id, trimmed)
            if (idsSeenForSong.add(id)) {
                crossRefs.add(SongGenreCrossRef(songId = song.id, genreId = id))
            }
        }
    }

    val genres = genreNameById.map { (id, name) ->
        GenreEntity(id = id, name = name, matchKey = name.genreMatchKey())
    }

    return GenreProcessResult(genres = genres, crossRefs = crossRefs)
}
