package com.theveloper.pixelplay.data.sonos.protocol

internal enum class TransportErrorKind {
    UNSUPPORTED_FORMAT,
    CORRUPT_FILE,
    LOST_CONNECTION,
    CANT_REACH_SERVER,
    RESOURCE_GONE,
    ACCESS_DENIED,
    OTHER,
}

/** A playback error the speaker reported by event. [trackLabel] is the file or title that failed. */
internal data class TransportError(
    val kind: TransportErrorKind,
    val trackLabel: String?,
)

internal object TransportErrors {

    private const val OK_STATUS = "OK"
    private const val TRACK_LABEL_FIELD = 2
    private val HOST_AND_PORT = Regex("""[\w.\-]+:\d+""")

    private val KINDS_BY_STATUS = mapOf(
        "ERROR_UNSUPPORTED_FORMAT" to TransportErrorKind.UNSUPPORTED_FORMAT,
        "ERROR_CORRUPT_FILE" to TransportErrorKind.CORRUPT_FILE,
        "ERROR_LOST_CONNECTION" to TransportErrorKind.LOST_CONNECTION,
        "ERROR_CANT_REACH_SERVER" to TransportErrorKind.CANT_REACH_SERVER,
        "ERROR_RESOURCE_NO_LONGER_AVAILABLE" to TransportErrorKind.RESOURCE_GONE,
        "ERROR_ACCESS_DENIED" to TransportErrorKind.ACCESS_DENIED,
    )

    /** Null when [status] is absent or `OK`. */
    fun parse(status: String?, description: String?): TransportError? {
        val code = status?.trim()?.takeIf { it.isNotEmpty() && it != OK_STATUS } ?: return null
        return TransportError(
            kind = KINDS_BY_STATUS[code] ?: TransportErrorKind.OTHER,
            trackLabel = description?.let(::trackLabelOf),
        )
    }

    /**
     * The description reads `code,code,<file or title>,<host:port>,<url>`. The title may itself
     * contain commas, so the label runs up to the `host:port` field.
     */
    private fun trackLabelOf(description: String): String? {
        val fields = description.split(',')
        if (fields.size <= TRACK_LABEL_FIELD) return null
        val hostIndex = (TRACK_LABEL_FIELD + 1 until fields.size).firstOrNull { HOST_AND_PORT.matches(fields[it].trim()) }
        val labelFields = if (hostIndex == null) listOf(fields[TRACK_LABEL_FIELD]) else fields.subList(TRACK_LABEL_FIELD, hostIndex)
        return labelFields.joinToString(",").trim().takeIf { it.isNotEmpty() }
    }
}
