package com.theveloper.pixelplay.data.sonos.model

/**
 * What the user picks from the list: a group of rooms playing in sync (or a single speaker).
 * Commands go to [host], the group's coordinator, identified by [id].
 */
internal data class SonosDevice(
    val id: SonosUdn,
    val name: String,
    val host: String,
    val roomCount: Int,
)
