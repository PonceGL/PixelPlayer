package com.theveloper.pixelplay.data.worker

import com.theveloper.pixelplay.utils.splitByDelimiters
import java.text.Normalizer
import kotlin.math.absoluteValue

/**
 * Canonical spelling for genre names that are still different strings after case and
 * diacritic normalization but denote the same genre (abbreviations, alternate spellings).
 * Keys are already in [genreMatchKey]-normalized form (lowercase, diacritics folded).
 */
private val GENRE_ALIAS_CANONICAL: Map<String, String> = mapOf(
    "latn" to "latin"
)

private val COMBINING_DIACRITICAL_MARKS = Regex("\\p{Mn}+")

/** Decomposes accented characters and drops the combining marks, e.g. "reggaetón" -> "reggaeton". */
private fun String.withoutDiacritics(): String =
    COMBINING_DIACRITICAL_MARKS.replace(Normalizer.normalize(this, Normalizer.Form.NFD), "")

/**
 * Normalizes a genre name into a stable identity key: case-insensitive, diacritic-insensitive,
 * and resolved against a small curated alias table for known abbreviations/spelling variants
 * (e.g. "Latn" -> "latin"). Two genre names with the same key are the same genre for grouping.
 */
internal fun String.genreMatchKey(): String {
    val folded = this.trim().lowercase().withoutDiacritics()
    return GENRE_ALIAS_CANONICAL[folded] ?: folded
}

/**
 * Resolves the individual genre names for one song, preferring pre-split tag values (a file's
 * own multi-value GENRE tag entries) over splitting the single raw genre string — mirrors
 * [resolveArtistsForSong]'s precedence for the ARTISTS tag.
 */
internal fun resolveGenresForSong(
    genresFromTag: List<String>,
    rawGenreName: String,
    genreDelimiters: List<String>,
    wordDelimiters: List<String> = emptyList()
): List<String> {
    val fromTag = genresFromTag.map { it.trim() }.filter { it.isNotEmpty() }
    if (fromTag.isNotEmpty()) {
        return fromTag
    }
    return rawGenreName.splitByDelimiters(genreDelimiters, wordDelimiters)
}

/**
 * Deterministic negative id derived from an already-[genreMatchKey]-normalized key, mirroring
 * the synthetic artist id scheme (see SyncWorker) but hashing the normalized key instead of the
 * raw display name — two names that fold to the same key (different case, e.g.) always produce
 * the same id, even before either has been persisted, unlike the artist scheme's hash-by-display
 * -name gap.
 */
internal fun genreIdFromMatchKey(matchKey: String): Long {
    val synthId = -(matchKey.hashCode().toLong().absoluteValue)
    return if (synthId == 0L) -1L else synthId
}
