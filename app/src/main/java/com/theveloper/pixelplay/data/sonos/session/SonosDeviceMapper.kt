package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.model.SonosGroup

internal object SonosDeviceMapper {

    private const val ROOM_SEPARATOR = " + "

    /** One device per group, sorted so the same speakers always give the same list. */
    fun fromGroups(groups: List<SonosGroup>): List<SonosDevice> = merge(groups.mapNotNull(::deviceOf))

    /** Devices without repeats, in the order shown to the user. */
    fun merge(devices: List<SonosDevice>): List<SonosDevice> = devices
        .distinctBy { it.id }
        .sortedWith(compareBy<SonosDevice, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id.value })

    private fun deviceOf(group: SonosGroup): SonosDevice? {
        val coordinator = group.coordinatorMember() ?: return null
        val rooms = group.members.filter { it.isVisible }.map { it.roomName }.distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .ifEmpty { listOf(coordinator.roomName) }
        return SonosDevice(
            id = group.coordinator,
            name = rooms.joinToString(ROOM_SEPARATOR),
            host = coordinator.host,
            roomCount = rooms.size,
        )
    }
}
