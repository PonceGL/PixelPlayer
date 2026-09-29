package com.theveloper.pixelplay.data.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository

class ArtistParsingUtilsTest {

    @Test
    fun `default delimiters preserve ampersand slash comma and plus inside artist names`() {
        assertEquals(
            listOf("W&W"),
            collectArtistNames(
                rawArtistName = "W&W",
                title = "Rave Culture",
                artistDelimiters = UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS,
                wordDelimiters = UserPreferencesRepository.DEFAULT_ARTIST_WORD_DELIMITERS
            )
        )
        assertEquals(
            listOf("AC/DC"),
            collectArtistNames(
                rawArtistName = "AC/DC",
                title = "Back In Black",
                artistDelimiters = UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS,
                wordDelimiters = UserPreferencesRepository.DEFAULT_ARTIST_WORD_DELIMITERS
            )
        )
        assertEquals(
            listOf("Lost & Found"),
            collectArtistNames(
                rawArtistName = "Lost & Found",
                title = "Found",
                artistDelimiters = UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS,
                wordDelimiters = UserPreferencesRepository.DEFAULT_ARTIST_WORD_DELIMITERS
            )
        )
        assertEquals(
            listOf("Black Country, New Road"),
            collectArtistNames(
                rawArtistName = "Black Country, New Road",
                title = "Track X",
                artistDelimiters = UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS,
                wordDelimiters = UserPreferencesRepository.DEFAULT_ARTIST_WORD_DELIMITERS
            )
        )
    }

    @Test
    fun `choosePreferredArtistName prefers media store when it contains more artists`() {
        val result =
            choosePreferredArtistName(
                localArtistName = "Calvin Harris",
                mediaStoreArtistName = "Calvin Harris, Pharrell Williams, Katy Perry, Big Sean, Funk Wav",
                artistDelimiters = listOf(",", "&"),
                wordDelimiters = emptyList()
            )

        assertEquals(
            "Calvin Harris, Pharrell Williams, Katy Perry, Big Sean, Funk Wav",
            result
        )
    }

    @Test
    fun `choosePreferredArtistName preserves richer local metadata when media store is reduced to primary`() {
        val result =
            choosePreferredArtistName(
                localArtistName = "Calvin Harris, Pharrell Williams, Katy Perry",
                mediaStoreArtistName = "Calvin Harris",
                artistDelimiters = listOf(",", "&"),
                wordDelimiters = emptyList()
            )

        assertEquals("Calvin Harris, Pharrell Williams, Katy Perry", result)
    }

    @Test
    fun `resolveArtistsForSong prefers ARTISTS tag over the ampersand ambiguous ARTIST field`() {
        // "Belinda & Natanael Cano" would wrongly split into 2 artists if "&" is
        // a delimiter, or wrongly stay as 1 if it isn't - the ARTISTS tag removes
        // the ambiguity entirely when present.
        val result = resolveArtistsForSong(
            artistsFromTag = listOf("Belinda", "Natanael Cano"),
            rawArtistName = "Belinda & Natanael Cano",
            title = "300 Noches",
            artistDelimiters = emptyList(),
            wordDelimiters = emptyList(),
            extractFromTitle = true
        )

        assertEquals(listOf("Belinda", "Natanael Cano"), result)
    }

    @Test
    fun `resolveArtistsForSong keeps a duo together when ARTISTS tag says so`() {
        // "Wisin & Yandel" IS one act - ARTISTS correctly keeps it as a single
        // entry even though "&" would otherwise look like a separator.
        val result = resolveArtistsForSong(
            artistsFromTag = listOf("Wisin & Yandel", "Zion", "Revol", "Cosculluela"),
            rawArtistName = "Wisin & Yandel, Zion & Revol feat. Cosculluela",
            title = "Ya Pasó",
            artistDelimiters = listOf("&", ","),
            wordDelimiters = listOf("feat."),
            extractFromTitle = true
        )

        assertEquals(listOf("Wisin & Yandel", "Zion", "Revol", "Cosculluela"), result)
    }

    @Test
    fun `resolveArtistsForSong falls back to collectArtistNames when ARTISTS tag is absent`() {
        val result = resolveArtistsForSong(
            artistsFromTag = emptyList(),
            rawArtistName = "Calvin Harris, Pharrell Williams",
            title = "Feels",
            artistDelimiters = listOf(","),
            wordDelimiters = emptyList(),
            extractFromTitle = false
        )

        assertEquals(listOf("Calvin Harris", "Pharrell Williams"), result)
    }

    @Test
    fun `resolveArtistsForSong falls back when ARTISTS tag is blank-only`() {
        val result = resolveArtistsForSong(
            artistsFromTag = listOf("  ", ""),
            rawArtistName = "Nek",
            title = "Se Me Olvido Otra Vez",
            artistDelimiters = emptyList(),
            wordDelimiters = emptyList(),
            extractFromTitle = false
        )

        assertEquals(listOf("Nek"), result)
    }

    @Test
    fun `collectArtistNames merges title features without duplicating existing artists`() {
        val result =
            collectArtistNames(
                rawArtistName = "Calvin Harris, Pharrell Williams",
                title = "Feels (feat. Katy Perry & Big Sean)",
                artistDelimiters = listOf(",", "&"),
                wordDelimiters = listOf("feat."),
                extractFromTitle = true
            )

        assertEquals(
            listOf("Calvin Harris", "Pharrell Williams", "Katy Perry", "Big Sean"),
            result
        )
    }
}
