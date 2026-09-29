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
}
