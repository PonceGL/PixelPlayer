package com.theveloper.pixelplay.data.sonos.model

internal enum class RepeatMode { OFF, ALL, ONE }

/**
 * Play modes of a Sonos queue. `SHUFFLE` without a suffix means shuffle *with* repeat of the
 * whole queue.
 */
internal enum class PlayMode(
    val upnpValue: String,
    val shuffle: Boolean,
    val repeat: RepeatMode,
) {
    NORMAL("NORMAL", shuffle = false, repeat = RepeatMode.OFF),
    REPEAT_ALL("REPEAT_ALL", shuffle = false, repeat = RepeatMode.ALL),
    REPEAT_ONE("REPEAT_ONE", shuffle = false, repeat = RepeatMode.ONE),
    SHUFFLE_NOREPEAT("SHUFFLE_NOREPEAT", shuffle = true, repeat = RepeatMode.OFF),
    SHUFFLE("SHUFFLE", shuffle = true, repeat = RepeatMode.ALL),
    SHUFFLE_REPEAT_ONE("SHUFFLE_REPEAT_ONE", shuffle = true, repeat = RepeatMode.ONE);

    companion object {
        fun fromUpnp(raw: String?): PlayMode? {
            val value = raw?.trim().orEmpty()
            return entries.firstOrNull { it.upnpValue == value }
        }

        fun of(shuffle: Boolean, repeat: RepeatMode): PlayMode =
            entries.first { it.shuffle == shuffle && it.repeat == repeat }
    }
}
