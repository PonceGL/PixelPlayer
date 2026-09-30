package com.theveloper.pixelplay.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ChipTextSegmentsTest {

    @Test
    fun emptyChipTextSegments_hasOneEmptyGapAndNoChips() {
        val segments = emptyChipTextSegments()

        assertEquals(listOf(""), segments.gaps)
        assertEquals(emptyList<String>(), segments.chips)
    }

    @Test
    fun addChip_appendsAtTheEndWithANewTrailingGap() {
        val segments = emptyChipTextSegments().addChip("Carín León")

        assertEquals(listOf("Carín León"), segments.chips)
        assertEquals(listOf("", ""), segments.gaps)
    }

    @Test
    fun addChip_seedsTheDefaultSeparatorIntoAnEmptyPrecedingGap() {
        val segments = emptyChipTextSegments()
            .addChip("Carín León")
            .addChip("Gabito Ballesteros")

        assertEquals(listOf("Carín León", "Gabito Ballesteros"), segments.chips)
        assertEquals(listOf("", ", ", ""), segments.gaps)
    }

    @Test
    fun addChip_neverOverwritesAGapTheUserAlreadyEdited() {
        val segments = emptyChipTextSegments()
            .addChip("Carín León")
            .setGap(1, " feat ")
            .addChip("Gabito Ballesteros")

        assertEquals(listOf("", " feat ", ""), segments.gaps)
    }

    @Test
    fun addChip_ignoresABlankName() {
        val segments = emptyChipTextSegments().addChip("   ")

        assertEquals(emptyChipTextSegments(), segments)
    }

    @Test
    fun removeChipAt_mergesItsTwoSurroundingGaps() {
        val segments = emptyChipTextSegments()
            .addChip("Carín León")
            .addChip("Gabito Ballesteros")
            .setGap(2, " (remix)")
            .removeChipAt(0)

        assertEquals(listOf("Gabito Ballesteros"), segments.chips)
        assertEquals(listOf(", ", " (remix)"), segments.gaps)
    }

    @Test
    fun removeChipAt_ignoresAnOutOfRangeIndex() {
        val segments = emptyChipTextSegments().addChip("Carín León")

        assertEquals(segments, segments.removeChipAt(5))
    }

    @Test
    fun setGap_replacesOnlyThatGapsText() {
        val segments = emptyChipTextSegments()
            .addChip("Carín León")
            .setGap(0, "The ")

        assertEquals(listOf("The ", ""), segments.gaps)
    }

    @Test
    fun toDisplayText_interleavesGapsAndChipsInOrder() {
        val segments = emptyChipTextSegments()
            .addChip("Carín León")
            .addChip("Gabito Ballesteros")
            .setGap(1, " feat ")
            .setGap(2, " (remix)")

        assertEquals("Carín León feat Gabito Ballesteros (remix)", segments.toDisplayText())
    }

    @Test
    fun toDisplayText_forASingleArtistWithNoExtraText() {
        val segments = emptyChipTextSegments().addChip("Carín León")

        assertEquals("Carín León", segments.toDisplayText())
    }

    @Test
    fun parseChipTextSegments_withNoChips_keepsTheWholeTextAsOneGap() {
        val segments = parseChipTextSegments("Carin Leon", emptyList())

        assertEquals(listOf("Carin Leon"), segments.gaps)
        assertEquals(emptyList<String>(), segments.chips)
    }

    @Test
    fun parseChipTextSegments_recoversGapsBetweenKnownChipNames() {
        val segments = parseChipTextSegments(
            "Carín León feat Gabito Ballesteros (remix)",
            listOf("Carín León", "Gabito Ballesteros")
        )

        assertEquals(listOf("Carín León", "Gabito Ballesteros"), segments.chips)
        assertEquals(listOf("", " feat ", " (remix)"), segments.gaps)
    }

    @Test
    fun parseChipTextSegments_fallsBackGracefullyWhenAChipNameIsMissingFromTheText() {
        // e.g. mismatched/legacy data where ARTIST and ARTISTS disagree - don't crash or guess,
        // just keep the original text and append the known chips with empty gaps.
        val segments = parseChipTextSegments("Some other text entirely", listOf("Carín León"))

        assertEquals(listOf("Carín León"), segments.chips)
        assertEquals(listOf("Some other text entirely", ""), segments.gaps)
    }
}
