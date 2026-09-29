package com.theveloper.pixelplay.data.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioMetadataReaderTest {

    @Test
    fun joinMultiValue_singleValue_returnsItUnchanged() {
        assertThat(AudioMetadataReader.joinMultiValue(arrayOf("Nek"))).isEqualTo("Nek")
    }

    @Test
    fun joinMultiValue_multipleValues_joinsWithSemicolonSpace() {
        // Regression: some files carry more than one physical ARTIST field
        // (e.g. two "ARTIST=" Vorbis comments) - taking only the first silently
        // drops the rest.
        assertThat(AudioMetadataReader.joinMultiValue(arrayOf("Alex Gargolas", "RKM & Ken-Y")))
            .isEqualTo("Alex Gargolas; RKM & Ken-Y")
    }

    @Test
    fun joinMultiValue_blankEntriesAmongValid_areSkipped() {
        assertThat(AudioMetadataReader.joinMultiValue(arrayOf("Nek", "", "  ")))
            .isEqualTo("Nek")
    }

    @Test
    fun joinMultiValue_allBlank_returnsNull() {
        assertThat(AudioMetadataReader.joinMultiValue(arrayOf("", "  "))).isNull()
    }

    @Test
    fun joinMultiValue_emptyArray_returnsNull() {
        assertThat(AudioMetadataReader.joinMultiValue(emptyArray())).isNull()
    }

    @Test
    fun joinMultiValue_null_returnsNull() {
        assertThat(AudioMetadataReader.joinMultiValue(null)).isNull()
    }

    @Test
    fun resolveArtistsFromTagPropertyMap_artistsTag_alreadySplitByTagLib_returnsAsIs() {
        // FLAC/Vorbis: TagLib already hands back separate array elements.
        val propertyMap = mapOf("ARTISTS" to arrayOf("Belinda", "Natanael Cano"))

        assertThat(AudioMetadataReader.resolveArtistsFromTagPropertyMap(propertyMap))
            .containsExactly("Belinda", "Natanael Cano")
            .inOrder()
    }

    @Test
    fun resolveArtistsFromTagPropertyMap_artistsTag_oneJoinedElement_getsSplit() {
        // Defensive: if the native path ever returns ARTISTS as a single
        // "; "-joined element for MP3/M4A instead of a clean array (unconfirmed
        // either way), it must still be split correctly.
        val propertyMap = mapOf("ARTISTS" to arrayOf("Belinda; Natanael Cano"))

        assertThat(AudioMetadataReader.resolveArtistsFromTagPropertyMap(propertyMap))
            .containsExactly("Belinda", "Natanael Cano")
            .inOrder()
    }

    @Test
    fun resolveArtistsFromTagPropertyMap_duoNameWithAmpersand_isNotTornApart() {
        val propertyMap = mapOf("ARTISTS" to arrayOf("Wisin & Yandel"))

        assertThat(AudioMetadataReader.resolveArtistsFromTagPropertyMap(propertyMap))
            .containsExactly("Wisin & Yandel")
    }

    @Test
    fun resolveArtistsFromTagPropertyMap_noArtistsTag_multiValueArtist_usesArtistValuesDirectly() {
        // No ARTISTS tag, but ARTIST itself has two physical values - just as
        // unambiguous as ARTISTS, must not be lost to a join+re-split round trip.
        val propertyMap = mapOf("ARTIST" to arrayOf("Alex Gargolas", "RKM & Ken-Y"))

        assertThat(AudioMetadataReader.resolveArtistsFromTagPropertyMap(propertyMap))
            .containsExactly("Alex Gargolas", "RKM & Ken-Y")
            .inOrder()
    }

    @Test
    fun resolveArtistsFromTagPropertyMap_noArtistsTag_singleValueArtist_returnsEmpty() {
        // A single ARTIST value is exactly the ambiguous case this feature exists
        // for - defer to the caller's own delimiter-based fallback instead of
        // guessing here.
        val propertyMap = mapOf("ARTIST" to arrayOf("Nek"))

        assertThat(AudioMetadataReader.resolveArtistsFromTagPropertyMap(propertyMap)).isEmpty()
    }

    @Test
    fun resolveArtistsFromTagPropertyMap_neitherTagPresent_returnsEmpty() {
        assertThat(AudioMetadataReader.resolveArtistsFromTagPropertyMap(emptyMap())).isEmpty()
    }
}
