package com.theveloper.pixelplay.data.sonos.model

/** Transport state reported by a Sonos speaker. */
internal enum class TransportState {
    PLAYING,
    PAUSED_PLAYBACK,
    STOPPED,
    TRANSITIONING,
    NO_MEDIA_PRESENT,
    UNKNOWN;

    companion object {
        fun fromUpnp(raw: String?): TransportState {
            val value = raw?.trim().orEmpty()
            return entries.firstOrNull { it != UNKNOWN && it.name == value } ?: UNKNOWN
        }
    }
}
