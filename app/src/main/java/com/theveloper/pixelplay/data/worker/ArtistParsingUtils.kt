package com.theveloper.pixelplay.data.worker

import com.theveloper.pixelplay.utils.extractArtistsFromTitle
import com.theveloper.pixelplay.utils.splitByDelimiters

/**
 * Resolves the artist names for one song, preferring the file's own ARTISTS
 * (plural) tag when present - it comes pre-split by the tagging tool (e.g.
 * MusicBrainz Picard), with no "&"/"feat." ambiguity, unlike splitting the
 * singular ARTIST field by delimiters. Falls back to [collectArtistNames]
 * (today's heuristic) when the file carries no such tag - unaffected either
 * way, so libraries with both kinds of files work correctly song by song.
 */
internal fun resolveArtistsForSong(
    artistsFromTag: List<String>,
    rawArtistName: String,
    title: String,
    artistDelimiters: List<String>,
    wordDelimiters: List<String> = emptyList(),
    extractFromTitle: Boolean = true
): List<String> {
    val fromTag = artistsFromTag.map { it.trim() }.filter { it.isNotEmpty() }
    if (fromTag.isNotEmpty()) {
        return fromTag
    }
    return collectArtistNames(rawArtistName, title, artistDelimiters, wordDelimiters, extractFromTitle)
}

internal fun collectArtistNames(
    rawArtistName: String,
    title: String,
    artistDelimiters: List<String>,
    wordDelimiters: List<String> = emptyList(),
    extractFromTitle: Boolean = true
): List<String> {
    val splitFromArtist = rawArtistName.splitByDelimiters(artistDelimiters, wordDelimiters)
    if (!extractFromTitle) {
        return splitFromArtist
    }

    val (_, titleArtists) = title.extractArtistsFromTitle(artistDelimiters, wordDelimiters)
    if (titleArtists.isEmpty()) {
        return splitFromArtist
    }

    val combined = splitFromArtist.toMutableList()
    titleArtists.forEach { titleArtist ->
        if (combined.none { it.equals(titleArtist, ignoreCase = true) }) {
            combined.add(titleArtist)
        }
    }
    return combined
}

/**
 * Builds the singular ARTIST display text from a list of individually-picked artists, using the
 * same "Oxford list" convention MusicBrainz Picard and most music apps use: a comma between every
 * pair except the last, joined by "&" - so the join character never repeats past the second
 * artist (e.g. "A, B & C", not "A & B & C"). This is the sole source of the singular display
 * text wherever artists are picked via chips - there is no manual override.
 */
internal fun buildDisplayArtistText(artists: List<String>): String = when (artists.size) {
    0 -> ""
    1 -> artists[0]
    2 -> "${artists[0]} & ${artists[1]}"
    else -> artists.dropLast(1).joinToString(", ") + " & " + artists.last()
}

internal fun choosePreferredArtistName(
    localArtistName: String,
    mediaStoreArtistName: String,
    artistDelimiters: List<String>,
    wordDelimiters: List<String> = emptyList()
): String {
    val localTrimmed = localArtistName.trim()
    val mediaTrimmed = mediaStoreArtistName.trim()

    if (localTrimmed.isBlank()) return mediaStoreArtistName
    if (mediaTrimmed.isBlank()) return localArtistName

    val localArtists = localTrimmed.splitByDelimiters(artistDelimiters, wordDelimiters)
    val mediaArtists = mediaTrimmed.splitByDelimiters(artistDelimiters, wordDelimiters)

    return when {
        mediaArtists.size > localArtists.size -> mediaStoreArtistName
        localArtists.size > mediaArtists.size -> localArtistName
        mediaTrimmed.length > localTrimmed.length -> mediaStoreArtistName
        localTrimmed.length > mediaTrimmed.length -> localArtistName
        else -> mediaStoreArtistName
    }
}
