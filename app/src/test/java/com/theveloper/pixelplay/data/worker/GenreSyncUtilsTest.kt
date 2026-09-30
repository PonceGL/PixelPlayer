package com.theveloper.pixelplay.data.worker

import com.theveloper.pixelplay.data.database.SongEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenreSyncUtilsTest {

    private fun testSong(id: Long, genre: String?) = SongEntity(
        id = id,
        title = "Title $id",
        artistName = "Artist",
        artistId = 1L,
        albumName = "Album",
        albumId = 1L,
        contentUriString = "content://audio/$id",
        albumArtUriString = null,
        duration = 1000L,
        genre = genre,
        filePath = "/music/$id.mp3",
        parentDirectoryPath = "/music"
    )

    @Test
    fun `splits a single song genre string by the configured delimiter`() {
        val result = preProcessAndDeduplicateGenres(
            songs = listOf(testSong(id = 1, genre = "Rock, Pop")),
            genreDelimiters = listOf(",")
        )

        assertEquals(2, result.genres.size)
        assertEquals(setOf("Rock", "Pop"), result.genres.map { it.name }.toSet())
        assertEquals(2, result.crossRefs.size)
        assertTrue(result.crossRefs.all { it.songId == 1L })
    }

    @Test
    fun `case variants across different songs resolve to the same genre id`() {
        val result = preProcessAndDeduplicateGenres(
            songs = listOf(
                testSong(id = 1, genre = "Rock"),
                testSong(id = 2, genre = "ROCK")
            ),
            genreDelimiters = listOf(",")
        )

        assertEquals(1, result.genres.size)
        assertEquals(2, result.crossRefs.size)
        assertEquals(result.crossRefs[0].genreId, result.crossRefs[1].genreId)
    }

    @Test
    fun `an already-persisted genre keeps its established display name`() {
        val rockId = genreIdFromMatchKey("rock".genreMatchKey())

        val result = preProcessAndDeduplicateGenres(
            songs = listOf(testSong(id = 1, genre = "ROCK")),
            genreDelimiters = listOf(","),
            existingGenreNames = mapOf(rockId to "Rock")
        )

        assertEquals(1, result.genres.size)
        assertEquals("Rock", result.genres.first().name)
    }

    @Test
    fun `prefers pre-split tag values over the raw genre string even with the wrong delimiter configured`() {
        val result = preProcessAndDeduplicateGenres(
            songs = listOf(testSong(id = 1, genre = "Rock;Pop")),
            genreDelimiters = listOf(","),
            genresFromTagBySongId = mapOf(1L to listOf("Rock", "Pop"))
        )

        assertEquals(2, result.genres.size)
        assertEquals(2, result.crossRefs.size)
    }

    @Test
    fun `a null genre produces no genres or cross-refs for that song`() {
        val result = preProcessAndDeduplicateGenres(
            songs = listOf(testSong(id = 1, genre = null)),
            genreDelimiters = listOf(",")
        )

        assertTrue(result.genres.isEmpty())
        assertTrue(result.crossRefs.isEmpty())
    }

    @Test
    fun `a blank genre produces no genres or cross-refs for that song`() {
        val result = preProcessAndDeduplicateGenres(
            songs = listOf(testSong(id = 1, genre = "   ")),
            genreDelimiters = listOf(",")
        )

        assertTrue(result.genres.isEmpty())
        assertTrue(result.crossRefs.isEmpty())
    }

    @Test
    fun `duplicate genres within the same song collapse into a single cross-ref`() {
        val result = preProcessAndDeduplicateGenres(
            songs = listOf(testSong(id = 1, genre = "Rock")),
            genreDelimiters = listOf(","),
            genresFromTagBySongId = mapOf(1L to listOf("Rock", "rock"))
        )

        assertEquals(1, result.genres.size)
        assertEquals(1, result.crossRefs.size)
    }
}
