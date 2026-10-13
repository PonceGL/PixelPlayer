package com.theveloper.pixelplay.data.sonos.protocol

import java.util.Locale
import kotlin.math.roundToLong

/** Conversions for the `H:MM:SS` durations UPnP uses. */
internal object UpnpTime {

    private val SECONDS_PATTERN = Regex("""\d{1,2}(\.\d+)?""")
    private const val MS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60
    private const val SECONDS_PER_HOUR = 3_600L

    /** Returns null for anything that is not a well formed duration, including `NOT_IMPLEMENTED`. */
    fun parseMs(raw: String?): Long? {
        val parts = raw?.trim()?.split(':') ?: return null
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val minutes = parts[1].toIntOrNull()?.takeIf { it in 0 until SECONDS_PER_MINUTE } ?: return null
        val secondsText = parts[2].takeIf { SECONDS_PATTERN.matches(it) } ?: return null
        val seconds = secondsText.toDouble().takeIf { it < SECONDS_PER_MINUTE } ?: return null
        val wholeSeconds = hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE
        return wholeSeconds * MS_PER_SECOND + (seconds * MS_PER_SECOND).roundToLong()
    }

    /** `H:MM:SS`, rounded to the nearest second. Null when there is no positive duration. */
    fun format(ms: Long): String? {
        if (ms <= 0) return null
        val totalSeconds = (ms.toDouble() / MS_PER_SECOND).roundToLong()
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    }
}
