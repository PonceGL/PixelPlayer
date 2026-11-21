package com.theveloper.pixelplay.data.sonos.session

/** What the phone does with its queue when the music comes back from the speaker. */
internal enum class QueueRestore {
    /** The phone's queue stays as it was. */
    KEEP_PHONE_QUEUE,

    /** The phone gets the queue the user made on the speaker. */
    USE_SPEAKER_QUEUE,

    /** The user took every song out on the speaker: the phone's queue is emptied too. */
    CLEAR,
}

/** Where in a queue the music goes back to when it leaves the speaker. */
internal object HandBackTarget {

    /** [speakerQueueSize] is null when the queue was not edited on the speaker. */
    fun queueToRestore(speakerQueueSize: Int?, restorePosition: Boolean): QueueRestore = when {
        speakerQueueSize == null || !restorePosition -> QueueRestore.KEEP_PHONE_QUEUE
        speakerQueueSize == 0 -> QueueRestore.CLEAR
        else -> QueueRestore.USE_SPEAKER_QUEUE
    }

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
