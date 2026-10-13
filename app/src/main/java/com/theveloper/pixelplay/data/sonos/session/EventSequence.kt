package com.theveloper.pixelplay.data.sonos.session

internal sealed interface SequenceCheck {
    data object InOrder : SequenceCheck

    /** One or more events were lost (or arrived repeated or late): reread the full state. */
    data object Gap : SequenceCheck

    /** The numbering restarted, which a new subscription does: the event carries the full state. */
    data object Restarted : SequenceCheck
}

/** Tracks the `SEQ` header of the NOTIFY messages of one subscription. */
internal data class EventSequence(val lastSeq: Long? = null) {

    fun accept(seq: Long): Pair<EventSequence, SequenceCheck> {
        val previous = lastSeq
        val check = when {
            previous == null -> if (seq == 0L) SequenceCheck.InOrder else SequenceCheck.Gap
            seq == 0L -> SequenceCheck.Restarted
            seq == previous + 1 -> SequenceCheck.InOrder
            else -> SequenceCheck.Gap
        }
        return EventSequence(seq) to check
    }
}
