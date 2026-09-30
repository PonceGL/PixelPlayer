package com.theveloper.pixelplay.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class TagChipAutocompleteFieldTest {

    @Test
    fun filterTagSuggestions_blankQuery_returnsAllExistingTags() {
        val result = filterTagSuggestions("", listOf("Rock", "Pop", "Jazz"))

        assertEquals(listOf("Rock", "Pop", "Jazz"), result)
    }

    @Test
    fun filterTagSuggestions_matchesCaseInsensitively() {
        val result = filterTagSuggestions("rock", listOf("Rock", "Pop"))

        assertEquals(listOf("Rock"), result)
    }

    @Test
    fun filterTagSuggestions_prefixMatchesRankBeforeMidStringMatches() {
        // "Pop" starts with "Po"; "K-pop" only contains it mid-string.
        val result = filterTagSuggestions("Po", listOf("K-pop", "Pop"))

        assertEquals(listOf("Pop", "K-pop"), result)
    }

    @Test
    fun filterTagSuggestions_noMatches_returnsEmpty() {
        val result = filterTagSuggestions("xyz", listOf("Rock", "Pop"))

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun filterTagSuggestions_excludesTagsAlreadyAdded() {
        val result = filterTagSuggestions("o", listOf("Rock", "Pop"), alreadyAdded = listOf("Rock"))

        assertEquals(listOf("Pop"), result)
    }

    @Test
    fun addTag_appendsATrimmedNewTag() {
        val result = addTag(listOf("Rock"), "  Pop  ")

        assertEquals(listOf("Rock", "Pop"), result)
    }

    @Test
    fun addTag_ignoresBlankCandidate() {
        val result = addTag(listOf("Rock"), "   ")

        assertEquals(listOf("Rock"), result)
    }

    @Test
    fun addTag_deduplicatesCaseInsensitively() {
        val result = addTag(listOf("Rock"), "rock")

        assertEquals(listOf("Rock"), result)
    }

    @Test
    fun removeTag_removesTheExactMatch() {
        val result = removeTag(listOf("Rock", "Pop"), "Rock")

        assertEquals(listOf("Pop"), result)
    }

    @Test
    fun splitPendingTagInput_noDelimiterTyped_returnsEverythingAsPending() {
        val result = splitPendingTagInput("Ro", listOf(","))

        assertEquals(emptyList<String>(), result.confirmedTags)
        assertEquals("Ro", result.remainingText)
    }

    @Test
    fun splitPendingTagInput_trailingDelimiter_confirmsTheTypedTag() {
        val result = splitPendingTagInput("Rock,", listOf(","))

        assertEquals(listOf("Rock"), result.confirmedTags)
        assertEquals("", result.remainingText)
    }

    @Test
    fun splitPendingTagInput_pastedMultiValueText_confirmsAllButTheLastSegment() {
        val result = splitPendingTagInput("Rock, Pop, Ja", listOf(","))

        assertEquals(listOf("Rock", "Pop"), result.confirmedTags)
        assertEquals("Ja", result.remainingText)
    }

    @Test
    fun splitPendingTagInput_pastedTextEndingInDelimiter_confirmsEverySegment() {
        val result = splitPendingTagInput("Rock, Pop,", listOf(","))

        assertEquals(listOf("Rock", "Pop"), result.confirmedTags)
        assertEquals("", result.remainingText)
    }

    @Test
    fun splitPendingTagInput_respectsMultipleConfiguredDelimiters() {
        val result = splitPendingTagInput("Rock;", listOf(",", ";", "/"))

        assertEquals(listOf("Rock"), result.confirmedTags)
        assertEquals("", result.remainingText)
    }
}
