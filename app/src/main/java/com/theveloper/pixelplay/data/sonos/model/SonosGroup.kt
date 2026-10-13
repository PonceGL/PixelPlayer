package com.theveloper.pixelplay.data.sonos.model

internal data class SonosMember(
    val udn: SonosUdn,
    val roomName: String,
    val host: String,
    val isVisible: Boolean,
)

/** Players playing in sync. Playback commands go to the [coordinator]. */
internal data class SonosGroup(
    val coordinator: SonosUdn,
    val members: List<SonosMember>,
) {
    fun coordinatorMember(): SonosMember? = members.firstOrNull { it.udn == coordinator }
}
