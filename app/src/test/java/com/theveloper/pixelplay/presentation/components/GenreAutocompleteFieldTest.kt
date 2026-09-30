package com.theveloper.pixelplay.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class GenreAutocompleteFieldTest {

    @Test
    fun filterGenreSuggestions_blankQuery_returnsAllExistingGenres() {
        val result = filterGenreSuggestions("", listOf("Rock", "Pop", "Jazz"))

        assertEquals(listOf("Rock", "Pop", "Jazz"), result)
    }

    @Test
    fun filterGenreSuggestions_matchesCaseInsensitively() {
        val result = filterGenreSuggestions("rock", listOf("Rock", "Pop"))

        assertEquals(listOf("Rock"), result)
    }

    @Test
    fun filterGenreSuggestions_prefixMatchesRankBeforeMidStringMatches() {
        // "Pop" starts with "Po"; "K-pop" only contains it mid-string.
        val result = filterGenreSuggestions("Po", listOf("K-pop", "Pop"))

        assertEquals(listOf("Pop", "K-pop"), result)
    }

    @Test
    fun filterGenreSuggestions_noMatches_returnsEmpty() {
        val result = filterGenreSuggestions("xyz", listOf("Rock", "Pop"))

        assertEquals(emptyList<String>(), result)
    }
}
