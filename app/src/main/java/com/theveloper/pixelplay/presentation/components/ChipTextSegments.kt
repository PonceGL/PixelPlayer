package com.theveloper.pixelplay.presentation.components

/**
 * A single-line field that interleaves free text with chip tokens - "[text] [chip] [text] [chip]
 * [text]" - so a user can type before, between, or after any chip (e.g. "feat", "(remix)")
 * without losing the chip's all-or-nothing removability. [gaps] always has exactly one more
 * element than [chips]: a leading gap, one between every pair of adjacent chips, and a trailing
 * gap - `gaps[i]` sits immediately before `chips[i]`, and `gaps.last()` sits after the last chip.
 */
internal data class ChipTextSegments(
    val gaps: List<String>,
    val chips: List<String>
)

internal fun emptyChipTextSegments(): ChipTextSegments = ChipTextSegments(gaps = listOf(""), chips = emptyList())

/**
 * Appends [name] as a new chip at the end. If the gap that will now sit right before it is still
 * empty, seeds it with [defaultSeparator] so the final text doesn't run two names together - but
 * never overwrites a gap the caller (the user) already put text into.
 */
internal fun ChipTextSegments.addChip(name: String, defaultSeparator: String = ", "): ChipTextSegments {
    val trimmed = name.trim()
    if (trimmed.isBlank()) return this
    val updatedGaps = gaps.toMutableList()
    val precedingGapIndex = updatedGaps.lastIndex
    if (chips.isNotEmpty() && updatedGaps[precedingGapIndex].isEmpty()) {
        updatedGaps[precedingGapIndex] = defaultSeparator
    }
    updatedGaps.add("")
    return ChipTextSegments(gaps = updatedGaps, chips = chips + trimmed)
}

/** Removes the chip at [index], merging its two surrounding gaps (preserving both texts). */
internal fun ChipTextSegments.removeChipAt(index: Int): ChipTextSegments {
    if (index !in chips.indices) return this
    val updatedChips = chips.toMutableList().also { it.removeAt(index) }
    val updatedGaps = gaps.toMutableList()
    updatedGaps[index] = updatedGaps[index] + updatedGaps[index + 1]
    updatedGaps.removeAt(index + 1)
    return ChipTextSegments(gaps = updatedGaps, chips = updatedChips)
}

/** Replaces the text of the gap at [index] - e.g. as the user types into it. */
internal fun ChipTextSegments.setGap(index: Int, text: String): ChipTextSegments {
    if (index !in gaps.indices) return this
    return copy(gaps = gaps.toMutableList().also { it[index] = text })
}

/** The final singular display string: every gap and chip, in order. */
internal fun ChipTextSegments.toDisplayText(): String = buildString {
    gaps.forEachIndexed { index, gap ->
        append(gap)
        if (index < chips.size) append(chips[index])
    }
}

/**
 * Reconstructs [ChipTextSegments] from an existing display string plus the chip names already
 * known to be in it, in order (e.g. a song's resolved artists) - so re-opening the editor shows
 * "feat"/"(remix)" text the user previously typed back in its original gap instead of losing it.
 * Falls back to keeping the whole text as one leading gap (chips appended with empty gaps after)
 * if a chip name can't be found verbatim in the text - e.g. legacy data where the singular and
 * plural tags disagree - rather than guessing at a split that might be wrong.
 */
internal fun parseChipTextSegments(displayText: String, chipNamesInOrder: List<String>): ChipTextSegments {
    if (chipNamesInOrder.isEmpty()) {
        return ChipTextSegments(gaps = listOf(displayText), chips = emptyList())
    }
    val gaps = mutableListOf<String>()
    var remaining = displayText
    for (name in chipNamesInOrder) {
        val index = remaining.indexOf(name)
        if (index == -1) {
            gaps.add(remaining)
            repeat(chipNamesInOrder.size - gaps.size + 1) { gaps.add("") }
            return ChipTextSegments(gaps = gaps, chips = chipNamesInOrder)
        }
        gaps.add(remaining.substring(0, index))
        remaining = remaining.substring(index + name.length)
    }
    gaps.add(remaining)
    return ChipTextSegments(gaps = gaps, chips = chipNamesInOrder)
}
