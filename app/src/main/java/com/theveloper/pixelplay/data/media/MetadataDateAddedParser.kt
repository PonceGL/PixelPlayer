package com.theveloper.pixelplay.data.media

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Parses the community "date added" convention that some users embed in file
 * metadata to survive moving their library between devices (MediaStore/file
 * DATE_ADDED resets on copy, this tag does not).
 *
 * The field is written as a generic user-text field, so its key differs by
 * container even though the value format is identical everywhere:
 *  - FLAC/OGG (Vorbis comment): "DATE_ADDED"
 *  - MP3/WAV (ID3v2 TXXX description), MP4 (freeform atom name), WMA (ASF
 *    content attribute): "Date Added"
 *
 * Value format is fixed: "yyyy-MM-dd HH:mm:ss", 19 characters, no offset -
 * a naive local timestamp with no timezone recorded anywhere in the chain
 * that produced it. There is no way to know which timezone it was written
 * in, so it is interpreted using this device's current default zone. That is
 * a deliberate, lossy assumption: the value is only ever used to order
 * songs relative to each other, never displayed as an absolute instant.
 */
object MetadataDateAddedParser {

    private const val NORMALIZED_KEY = "DATEADDED"
    private const val EXPECTED_LENGTH = 19
    // STRICT: the default SMART resolver silently rolls an invalid calendar date
    // (e.g. Feb 30th) into a nearby valid one instead of rejecting it - wrong for
    // a sort key, where a corrupt tag must fall back rather than land on a
    // plausible-looking but fabricated date.
    //
    // "uuuu" (proleptic year), not "yyyy" (year-of-era): under STRICT resolution
    // "y" needs an era in the pattern to resolve at all, so every value fails to
    // parse even when well-formed. "u" has no such requirement.
    private val FORMATTER: DateTimeFormatter = DateTimeFormatter
        .ofPattern("uuuu-MM-dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT)

    /** Looks the tag up by key in a TagLib-style property map (FLAC/OGG path). */
    fun parse(propertyMap: Map<String, Array<String>>): Long? {
        val rawValue = findRawValue(propertyMap) ?: return null
        return parse(rawValue)
    }

    /** Parses a raw tag value already extracted by container-specific means (JAudioTagger path). */
    fun parse(rawValue: String): Long? {
        if (rawValue.length != EXPECTED_LENGTH) return null
        return try {
            LocalDateTime.parse(rawValue, FORMATTER)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun findRawValue(propertyMap: Map<String, Array<String>>): String? =
        propertyMap.entries
            .firstOrNull { (key, _) -> normalizeKey(key) == NORMALIZED_KEY }
            ?.value
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun normalizeKey(key: String): String =
        key.replace("_", "").replace(" ", "").uppercase()
}
