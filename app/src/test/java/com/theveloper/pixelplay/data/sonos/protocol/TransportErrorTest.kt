package com.theveloper.pixelplay.data.sonos.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TransportErrorTest {

    @Test
    fun `every status the speaker reports maps to a kind`() {
        val expected = mapOf(
            "ERROR_UNSUPPORTED_FORMAT" to TransportErrorKind.UNSUPPORTED_FORMAT,
            "ERROR_CORRUPT_FILE" to TransportErrorKind.CORRUPT_FILE,
            "ERROR_LOST_CONNECTION" to TransportErrorKind.LOST_CONNECTION,
            "ERROR_CANT_REACH_SERVER" to TransportErrorKind.CANT_REACH_SERVER,
            "ERROR_RESOURCE_NO_LONGER_AVAILABLE" to TransportErrorKind.RESOURCE_GONE,
            "ERROR_ACCESS_DENIED" to TransportErrorKind.ACCESS_DENIED,
        )

        expected.forEach { (status, kind) ->
            assertThat(TransportErrors.parse(status, null)?.kind).isEqualTo(kind)
        }
    }

    @Test
    fun `unknown error codes are kept as other`() {
        assertThat(TransportErrors.parse("ERROR_SOMETHING_NEW", null)?.kind).isEqualTo(TransportErrorKind.OTHER)
    }

    @Test
    fun `OK and missing statuses are not errors`() {
        assertThat(TransportErrors.parse("OK", "8,0,x,1.2.3.4:80,h")).isNull()
        assertThat(TransportErrors.parse(null, null)).isNull()
        assertThat(TransportErrors.parse("  ", null)).isNull()
    }

    @Test
    fun `the third description field names the file`() {
        val error = TransportErrors.parse("ERROR_UNSUPPORTED_FORMAT", "8,0,aiff_24_48.aiff,192.168.1.20:8765,h")

        assertThat(error?.trackLabel).isEqualTo("aiff_24_48.aiff")
    }

    @Test
    fun `a title containing commas is kept whole`() {
        val error = TransportErrors.parse(
            "ERROR_LOST_CONNECTION",
            "8,0,Hello, World, Part 2,192.168.1.20:8765,http://192.168.1.20:8765/t/testtok1/a.flac",
        )

        assertThat(error?.trackLabel).isEqualTo("Hello, World, Part 2")
    }

    @Test
    fun `short or empty descriptions carry no label`() {
        assertThat(TransportErrors.parse("ERROR_CORRUPT_FILE", null)?.trackLabel).isNull()
        assertThat(TransportErrors.parse("ERROR_CORRUPT_FILE", "")?.trackLabel).isNull()
        assertThat(TransportErrors.parse("ERROR_CORRUPT_FILE", "8,0")?.trackLabel).isNull()
        assertThat(TransportErrors.parse("ERROR_CORRUPT_FILE", "8,0,,192.168.1.20:8765,h")?.trackLabel).isNull()
    }
}
