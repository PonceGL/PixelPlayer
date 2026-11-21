package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.SonosTransport
import com.theveloper.pixelplay.data.sonos.protocol.SonosService

/** The volume of a speaker as it reports it: a level from 0 to 100 and whether it is muted. */
internal data class SonosVolume(val level: Int, val muted: Boolean)

/**
 * Everything the app tells a speaker about its volume. Separate from the commander of the queue on purpose: that one
 * works through one command at a time and a load of the queue takes seconds, and the volume must not wait behind it.
 *
 * @param group the device is a group of several rooms: the volume is the group's, not one speaker's
 */
internal class SonosVolumeCommander(
    private val transport: SonosTransport,
    private val host: String,
    private val group: Boolean,
) {

    private val service get() = if (group) SonosService.GROUP_RENDERING_CONTROL else SonosService.RENDERING_CONTROL

    /** Sets the volume to [level], brought inside 0..100 (the speaker answers 402 to anything else). Gives the level sent. */
    suspend fun setVolume(level: Int): SonosResult<Int> {
        val clamped = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        val action = if (group) "SetGroupVolume" else "SetVolume"
        return send(action, "DesiredVolume" to clamped.toString()).map { clamped }
    }

    /** Changes the volume by [delta] points. The speaker keeps the result inside its range and says what it is now. */
    suspend fun adjust(delta: Int): SonosResult<Int> {
        if (group) {
            // A relative change of a group is made against a snapshot of the volumes of its rooms.
            send("SnapshotGroupVolume").let { if (it is SonosResult.Err) return it }
        }
        val action = if (group) "SetRelativeGroupVolume" else "SetRelativeVolume"
        return when (val result = send(action, "Adjustment" to delta.toString())) {
            is SonosResult.Err -> result
            is SonosResult.Ok -> result.value["NewVolume"]?.trim()?.toIntOrNull()?.let { SonosResult.Ok(it) }
                ?: SonosResult.Err(SonosFailure.MalformedResponse)
        }
    }

    suspend fun setMuted(muted: Boolean): SonosResult<Unit> {
        val action = if (group) "SetGroupMute" else "SetMute"
        return send(action, "DesiredMute" to if (muted) "1" else "0").map { }
    }

    /** What the speaker has now. */
    suspend fun read(): SonosResult<SonosVolume> {
        val level = when (val result = send(if (group) "GetGroupVolume" else "GetVolume")) {
            is SonosResult.Err -> return result
            is SonosResult.Ok -> result.value["CurrentVolume"]?.trim()?.toIntOrNull() ?: return SonosResult.Err(SonosFailure.MalformedResponse)
        }
        val muted = when (val result = send(if (group) "GetGroupMute" else "GetMute")) {
            is SonosResult.Err -> return result
            is SonosResult.Ok -> when (result.value["CurrentMute"]?.trim()) {
                "1" -> true
                "0" -> false
                else -> return SonosResult.Err(SonosFailure.MalformedResponse)
            }
        }
        return SonosResult.Ok(SonosVolume(level, muted))
    }

    private suspend fun send(action: String, vararg arguments: Pair<String, String>): SonosResult<Map<String, String>> {
        // A single speaker's volume is per channel and the one that counts is the master; a group's has no channel.
        val channel = if (group) emptyList() else listOf("Channel" to "Master")
        return transport.call(host, service, action, listOf("InstanceID" to "0") + channel + arguments)
    }

    private inline fun <T, R> SonosResult<T>.map(transform: (T) -> R): SonosResult<R> = when (this) {
        is SonosResult.Ok -> SonosResult.Ok(transform(value))
        is SonosResult.Err -> this
    }

    private companion object {
        const val MIN_LEVEL = 0
        const val MAX_LEVEL = 100
    }
}
