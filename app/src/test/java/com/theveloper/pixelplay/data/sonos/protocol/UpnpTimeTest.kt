package com.theveloper.pixelplay.data.sonos.protocol

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.jupiter.api.Test

class UpnpTimeTest {

    @Test
    fun `parses H MM SS into milliseconds`() {
        assertThat(UpnpTime.parseMs("0:00:30")).isEqualTo(30_000L)
        assertThat(UpnpTime.parseMs("1:02:03")).isEqualTo(3_723_000L)
        assertThat(UpnpTime.parseMs("10:00:00")).isEqualTo(36_000_000L)
    }

    @Test
    fun `keeps fractional seconds`() {
        assertThat(UpnpTime.parseMs("0:00:01.500")).isEqualTo(1_500L)
    }

    @Test
    fun `accepts a single digit second`() {
        assertThat(UpnpTime.parseMs("0:03:7")).isEqualTo(187_000L)
    }

    @Test
    fun `values the speaker uses for unknown are null`() {
        assertThat(UpnpTime.parseMs("NOT_IMPLEMENTED")).isNull()
        assertThat(UpnpTime.parseMs("")).isNull()
        assertThat(UpnpTime.parseMs("   ")).isNull()
        assertThat(UpnpTime.parseMs(null)).isNull()
    }

    @Test
    fun `malformed values are null`() {
        assertThat(UpnpTime.parseMs("1:2")).isNull()
        assertThat(UpnpTime.parseMs("a:b:c")).isNull()
        assertThat(UpnpTime.parseMs("0:61:00")).isNull()
        assertThat(UpnpTime.parseMs("0:00:61")).isNull()
        assertThat(UpnpTime.parseMs("-1:00:00")).isNull()
        assertThat(UpnpTime.parseMs("0:00:NaN")).isNull()
    }

    @Test
    fun `formats milliseconds as H MM SS rounding to the nearest second`() {
        assertThat(UpnpTime.format(30_000L)).isEqualTo("0:00:30")
        assertThat(UpnpTime.format(3_723_499L)).isEqualTo("1:02:03")
        assertThat(UpnpTime.format(3_723_500L)).isEqualTo("1:02:04")
    }

    @Test
    fun `non positive durations have no representation`() {
        assertThat(UpnpTime.format(0L)).isNull()
        assertThat(UpnpTime.format(-5L)).isNull()
    }

    @Test
    fun `formatting ignores the device locale digits`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar-SA-u-nu-arab"))
        try {
            assertThat(UpnpTime.format(3_723_000L)).isEqualTo("1:02:03")
        } finally {
            Locale.setDefault(previous)
        }
    }
}
