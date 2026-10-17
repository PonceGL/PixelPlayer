package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.model.SonosDevice

/** Where a play the user asks for from a list goes. */
internal sealed interface PlayTarget {
    data object Phone : PlayTarget
    data object Cast : PlayTarget
    data class Speaker(val device: SonosDevice) : PlayTarget
}

/**
 * A new play follows the output that is on: a speaker session that is on or starting takes it (a play during
 * "connecting" replaces that attempt); a session that failed is not an output.
 */
internal fun playTargetOf(connection: SonosConnection, castActive: Boolean): PlayTarget = when {
    castActive -> PlayTarget.Cast
    connection is SonosConnection.Active -> PlayTarget.Speaker(connection.device)
    connection is SonosConnection.Connecting -> PlayTarget.Speaker(connection.device)
    else -> PlayTarget.Phone
}
