package com.theveloper.pixelplay.data.sonos.player

import androidx.media3.common.Player

/**
 * Whether a change of song is the end of the one that was playing. A local player says so (automatic transition). A
 * speaker moves on by itself without the exact position media3 needs to call it that, so for it a change of the list
 * is the end of the song.
 */
fun endedByItself(transitionReason: Int, speakerSession: Boolean): Boolean =
    transitionReason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
        (speakerSession && transitionReason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
