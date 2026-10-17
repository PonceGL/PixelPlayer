package com.theveloper.pixelplay.data.sonos.session

/** What a request to connect to a speaker does with the position and with playing, depending on where its queue comes from. */
internal object SonosConnectPlan {

    /**
     * Whether the song and second of the session being left are put back on the phone first. A queue that was just
     * made for the speaker has its own start: the old place would send it to the wrong song.
     */
    fun restoresPosition(freshQueue: Boolean, hadSession: Boolean): Boolean = hadSession && !freshQueue

    /**
     * Whether the speaker starts playing. A new queue does. Otherwise the intent of an earlier attempt that is still
     * going wins, then what the speaker being left was doing, then the phone.
     */
    fun plays(freshQueue: Boolean, pending: Boolean?, speakerWasPlaying: Boolean?, phoneWasPlaying: Boolean): Boolean =
        freshQueue || (pending ?: speakerWasPlaying ?: phoneWasPlaying)
}
