package com.theveloper.pixelplay.data.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenreParsingUtilsTest {

    @Test
    fun `genreMatchKey lowercases regardless of input casing`() {
        assertEquals("rock".genreMatchKey(), "ROCK".genreMatchKey())
        assertEquals("rock".genreMatchKey(), "Rock".genreMatchKey())
    }

    @Test
    fun `genreMatchKey folds accented characters`() {
        assertEquals("Reggaeton".genreMatchKey(), "Reggaetón".genreMatchKey())
    }

    @Test
    fun `genreMatchKey resolves known abbreviation aliases`() {
        assertEquals("Latin".genreMatchKey(), "Latn".genreMatchKey())
    }

    @Test
    fun `genreMatchKey trims surrounding whitespace`() {
        assertEquals("rock", "  Rock  ".genreMatchKey())
    }

    @Test
    fun `resolveGenresForSong splits raw genre by configured character delimiters`() {
        val result = resolveGenresForSong(
            genresFromTag = emptyList(),
            rawGenreName = "Rock;Pop",
            genreDelimiters = listOf(";")
        )

        assertEquals(listOf("Rock", "Pop"), result)
    }

    @Test
    fun `resolveGenresForSong prefers pre-split tag values over splitting the raw string`() {
        val result = resolveGenresForSong(
            genresFromTag = listOf("Rock", "Pop"),
            rawGenreName = "Rock/Pop",
            genreDelimiters = listOf("/")
        )

        assertEquals(listOf("Rock", "Pop"), result)
    }

    @Test
    fun `resolveGenresForSong falls back to raw string when tag has no pre-split values`() {
        val result = resolveGenresForSong(
            genresFromTag = emptyList(),
            rawGenreName = "Rock, Pop",
            genreDelimiters = listOf(",")
        )

        assertEquals(listOf("Rock", "Pop"), result)
    }

    @Test
    fun `resolveGenresForSong respects escaped delimiters`() {
        // ESCAPE_SEQUENCE is two literal backslashes ("\\\\" in Kotlin source = 2 chars),
        // so escaping the "/" delimiter here needs 4 backslash characters in source.
        val result = resolveGenresForSong(
            genresFromTag = emptyList(),
            rawGenreName = "Drum\\\\/Bass",
            genreDelimiters = listOf("/")
        )

        assertEquals(listOf("Drum/Bass"), result)
    }

    @Test
    fun `genreIdFromMatchKey is deterministic for the same match key`() {
        assertEquals(genreIdFromMatchKey("rock"), genreIdFromMatchKey("rock"))
    }

    @Test
    fun `genreIdFromMatchKey is the same for case and accent variants that share a match key`() {
        // "ROCK" and "rock" fold to the same match key, so they must resolve to the
        // same id even when both are brand new in the same sync batch - unlike the
        // artist id scheme (hashes the raw display name), this hashes the already
        // case/accent-normalized key so no case-sensitivity gap is possible here.
        assertEquals(genreIdFromMatchKey("ROCK".genreMatchKey()), genreIdFromMatchKey("rock".genreMatchKey()))
        assertEquals(genreIdFromMatchKey("Reggaetón".genreMatchKey()), genreIdFromMatchKey("Reggaeton".genreMatchKey()))
    }

    @Test
    fun `genreIdFromMatchKey is negative, never zero`() {
        assertTrue(genreIdFromMatchKey("rock") < 0)
        assertTrue(genreIdFromMatchKey("") < 0)
    }

    @Test
    fun `genreIdFromMatchKey differs for different match keys`() {
        assertTrue(genreIdFromMatchKey("rock") != genreIdFromMatchKey("pop"))
    }
}
