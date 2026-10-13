package com.theveloper.pixelplay.data.sonos.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SonosFormatPolicyTest {

    private fun verdict(mime: String?, rateHz: Int? = null, bits: Int? = null) =
        SonosFormatPolicy.evaluate(AudioFormat(mime, rateHz, bits))

    private fun unsupported(reason: UnsupportedReason) = FormatVerdict.Unsupported(reason)

    @Test
    fun `flac up to 24 bit and 48 kHz plays`() {
        assertThat(verdict("audio/flac", 48_000, 24)).isEqualTo(FormatVerdict.Supported)
        assertThat(verdict("audio/flac", 44_100, 16)).isEqualTo(FormatVerdict.Supported)
        assertThat(verdict("audio/x-flac", 44_100)).isEqualTo(FormatVerdict.Supported)
    }

    @Test
    fun `flac above 48 kHz does not play`() {
        assertThat(verdict("audio/flac", 96_000, 24)).isEqualTo(unsupported(UnsupportedReason.SAMPLE_RATE_TOO_HIGH))
        assertThat(verdict("audio/flac", 192_000, 24)).isEqualTo(unsupported(UnsupportedReason.SAMPLE_RATE_TOO_HIGH))
    }

    @Test
    fun `flac deeper than 24 bit does not play`() {
        assertThat(verdict("audio/flac", 48_000, 32)).isEqualTo(unsupported(UnsupportedReason.BIT_DEPTH_TOO_HIGH))
    }

    @Test
    fun `flac without a known sample rate cannot be judged`() {
        assertThat(verdict("audio/flac", null, 24)).isEqualTo(FormatVerdict.Unknown)
    }

    @Test
    fun `alac follows the same limits as flac`() {
        assertThat(verdict("audio/alac", 48_000, 24)).isEqualTo(FormatVerdict.Supported)
        assertThat(verdict("audio/alac", 96_000, 24)).isEqualTo(unsupported(UnsupportedReason.SAMPLE_RATE_TOO_HIGH))
    }

    @Test
    fun `an mp4 container may hold aac or alac and both play within the limits`() {
        assertThat(verdict("audio/mp4", 44_100)).isEqualTo(FormatVerdict.Supported)
        assertThat(verdict("audio/x-m4a", 48_000)).isEqualTo(FormatVerdict.Supported)
        assertThat(verdict("audio/mp4", 96_000)).isEqualTo(unsupported(UnsupportedReason.SAMPLE_RATE_TOO_HIGH))
        assertThat(verdict("audio/mp4", null)).isEqualTo(FormatVerdict.Unknown)
    }

    @Test
    fun `wav and aiff play only up to 16 bit`() {
        assertThat(verdict("audio/wav", 44_100, 16)).isEqualTo(FormatVerdict.Supported)
        assertThat(verdict("audio/x-wav", 48_000, 16)).isEqualTo(FormatVerdict.Supported)
        assertThat(verdict("audio/aiff", 44_100, 16)).isEqualTo(FormatVerdict.Supported)
        assertThat(verdict("audio/wav", 48_000, 24)).isEqualTo(unsupported(UnsupportedReason.BIT_DEPTH_TOO_HIGH))
        assertThat(verdict("audio/x-aiff", 48_000, 24)).isEqualTo(unsupported(UnsupportedReason.BIT_DEPTH_TOO_HIGH))
    }

    @Test
    fun `the sample rate limit is reported before the bit depth`() {
        assertThat(verdict("audio/wav", 96_000, 24)).isEqualTo(unsupported(UnsupportedReason.SAMPLE_RATE_TOO_HIGH))
    }

    @Test
    fun `wav without a known bit depth cannot be judged`() {
        assertThat(verdict("audio/wav", 44_100, null)).isEqualTo(FormatVerdict.Unknown)
    }

    @Test
    fun `lossy formats play`() {
        assertThat(verdict("audio/mpeg", 44_100)).isEqualTo(FormatVerdict.Supported)
        assertThat(verdict("audio/mpeg", null)).isEqualTo(FormatVerdict.Supported)
        assertThat(verdict("audio/aac", 44_100)).isEqualTo(FormatVerdict.Supported)
        assertThat(verdict("audio/mp4a-latm", null)).isEqualTo(FormatVerdict.Supported)
        assertThat(verdict("audio/vorbis", 44_100)).isEqualTo(FormatVerdict.Supported)
    }

    @Test
    fun `opus does not play`() {
        assertThat(verdict("audio/opus", 48_000)).isEqualTo(unsupported(UnsupportedReason.CODEC_NOT_SUPPORTED))
    }

    @Test
    fun `ogg may hold vorbis or opus so it cannot be judged`() {
        assertThat(verdict("audio/ogg", 48_000)).isEqualTo(FormatVerdict.Unknown)
    }

    @Test
    fun `wma is inconclusive`() {
        assertThat(verdict("audio/x-ms-wma", 44_100)).isEqualTo(FormatVerdict.Unknown)
    }

    @Test
    fun `missing or unrecognised mime types cannot be judged`() {
        assertThat(verdict(null, 44_100)).isEqualTo(FormatVerdict.Unknown)
        assertThat(verdict("  ", 44_100)).isEqualTo(FormatVerdict.Unknown)
        assertThat(verdict("audio/x-foo", 44_100)).isEqualTo(FormatVerdict.Unknown)
    }

    @Test
    fun `mime matching ignores case and parameters`() {
        assertThat(verdict("AUDIO/FLAC; codecs=flac", 96_000)).isEqualTo(unsupported(UnsupportedReason.SAMPLE_RATE_TOO_HIGH))
    }
}
