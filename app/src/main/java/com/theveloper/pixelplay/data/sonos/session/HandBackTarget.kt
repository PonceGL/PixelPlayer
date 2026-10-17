package com.theveloper.pixelplay.data.sonos.session

/** Where in a queue the music goes back to when it leaves the speaker. */
internal object HandBackTarget {

    /**
     * The place of [songId] in [queueIds]: the one the player was showing if it is that song (the same song can be in
     * the queue twice), otherwise its first copy. Null when the song is not in the queue.
     */
    fun of(queueIds: List<String>, songId: String?, shownIndex: Int?): Int? {
        if (songId == null) return null
        if (shownIndex != null && queueIds.getOrNull(shownIndex) == songId) return shownIndex
        return queueIds.indexOf(songId).takeIf { it >= 0 }
    }
}
