package com.theveloper.pixelplay.data.media

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

class MetadataDateAddedParserTest {

    private fun expectedMillisFor(dateTime: String): Long =
        LocalDateTime.parse(dateTime.replace(' ', 'T'))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun parse_vorbisCommentKey_returnsParsedMillis() {
        val propertyMap = mapOf("DATE_ADDED" to arrayOf("2026-06-20 07:36:45"))

        val result = MetadataDateAddedParser.parse(propertyMap)

        assertThat(result).isEqualTo(expectedMillisFor("2026-06-20 07:36:45"))
    }

    @Test
    fun parse_id3Txxx_titleCaseWithSpace_returnsParsedMillis() {
        val propertyMap = mapOf("Date Added" to arrayOf("2026-09-28 11:14:18"))

        val result = MetadataDateAddedParser.parse(propertyMap)

        assertThat(result).isEqualTo(expectedMillisFor("2026-09-28 11:14:18"))
    }

    @Test
    fun parse_lowercaseKey_asNormalizedByMutagenOnRead_returnsParsedMillis() {
        val propertyMap = mapOf("date_added" to arrayOf("2026-01-05 00:00:00"))

        val result = MetadataDateAddedParser.parse(propertyMap)

        assertThat(result).isEqualTo(expectedMillisFor("2026-01-05 00:00:00"))
    }

    @Test
    fun parse_keyMissing_returnsNull() {
        val propertyMap = mapOf("TITLE" to arrayOf("Some Song"))

        assertThat(MetadataDateAddedParser.parse(propertyMap)).isNull()
    }

    @Test
    fun parse_emptyPropertyMap_returnsNull() {
        assertThat(MetadataDateAddedParser.parse(emptyMap())).isNull()
    }

    @Test
    fun parse_blankValue_returnsNull() {
        val propertyMap = mapOf("DATE_ADDED" to arrayOf(""))

        assertThat(MetadataDateAddedParser.parse(propertyMap)).isNull()
    }

    @Test
    fun parse_emptyValueArray_returnsNull() {
        val propertyMap = mapOf("DATE_ADDED" to emptyArray<String>())

        assertThat(MetadataDateAddedParser.parse(propertyMap)).isNull()
    }

    @Test
    fun parse_iso8601WithTSeparator_isRejected() {
        val propertyMap = mapOf("DATE_ADDED" to arrayOf("2026-06-20T07:36:45"))

        assertThat(MetadataDateAddedParser.parse(propertyMap)).isNull()
    }

    @Test
    fun parse_valueWithFractionalSeconds_isRejected() {
        val propertyMap = mapOf("DATE_ADDED" to arrayOf("2026-06-20 07:36:45.123"))

        assertThat(MetadataDateAddedParser.parse(propertyMap)).isNull()
    }

    @Test
    fun parse_valueWithUtcOffset_isRejected() {
        val propertyMap = mapOf("DATE_ADDED" to arrayOf("2026-06-20 07:36:45+00:00"))

        assertThat(MetadataDateAddedParser.parse(propertyMap)).isNull()
    }

    @Test
    fun parse_garbageValue_returnsNullInsteadOfThrowing() {
        val propertyMap = mapOf("DATE_ADDED" to arrayOf("not-a-date"))

        assertThat(MetadataDateAddedParser.parse(propertyMap)).isNull()
    }

    @Test
    fun parse_invalidCalendarDate_returnsNullInsteadOfThrowing() {
        // February 30th does not exist - must be rejected, not throw.
        val propertyMap = mapOf("DATE_ADDED" to arrayOf("2026-02-30 07:36:45"))

        assertThat(MetadataDateAddedParser.parse(propertyMap)).isNull()
    }

    @Test
    fun parse_rawStringOverload_validValue_returnsParsedMillis() {
        val result = MetadataDateAddedParser.parse("2026-09-28 11:14:18")

        assertThat(result).isEqualTo(expectedMillisFor("2026-09-28 11:14:18"))
    }

    @Test
    fun parse_rawStringOverload_invalidValue_returnsNull() {
        assertThat(MetadataDateAddedParser.parse("not-a-date")).isNull()
    }

    @Test
    fun parse_mp4FreeformStyleKey_titleCaseWithSpace_returnsParsedMillis() {
        // MP4 freeform atoms (----:com.apple.iTunes:Date Added) and WMA/ASF content
        // attributes surface with the same "Date Added" description text as ID3 TXXX.
        val propertyMap = mapOf("Date Added" to arrayOf("2026-03-15 18:00:00"))

        val result = MetadataDateAddedParser.parse(propertyMap)

        assertThat(result).isEqualTo(expectedMillisFor("2026-03-15 18:00:00"))
    }
}
