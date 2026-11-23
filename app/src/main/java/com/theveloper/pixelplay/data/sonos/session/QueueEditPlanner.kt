package com.theveloper.pixelplay.data.sonos.session

/**
 * One change to the speaker's queue. Track numbers start at 1 and mean the queue as it is when the edit is applied,
 * after the edits before it.
 */
internal sealed interface QueueEdit {
    /** Takes out [count] songs from track [first]. */
    data class Remove(val first: Int, val count: Int) : QueueEdit

    /** Moves [count] songs from track [first] to just before the track that is number [before] now (length + 1 is the end). */
    data class Move(val first: Int, val count: Int, val before: Int) : QueueEdit

    /** Puts the songs of the new queue at [targetIndices] from track [position] on (0 is the end). */
    data class Insert(val position: Int, val targetIndices: List<Int>) : QueueEdit

    /** Nothing of what is on the speaker can be kept: the new queue is loaded from scratch. */
    data object Reload : QueueEdit
}

/**
 * Turns "the queue should be this now" into the fewest changes to the speaker's queue that leave the song that plays
 * alone. A song is told apart from another copy of itself by its place among its copies, so a queue with the same
 * song twice is no problem.
 */
internal object QueueEditPlanner {

    /**
     * @param current the songs on the speaker, by id, in order
     * @param target the songs it should have
     * @param playing the place in [current] of the song that plays, or null when it is not known
     */
    fun plan(current: List<String>, target: List<String>, playing: Int?): List<QueueEdit> {
        val have = keysOf(current)
        val want = keysOf(target)
        if (have == want) return emptyList()

        var prefix = 0
        while (prefix < have.size && prefix < want.size && have[prefix] == want[prefix]) prefix++
        var suffix = 0
        while (suffix < have.size - prefix && suffix < want.size - prefix &&
            have[have.size - 1 - suffix] == want[want.size - 1 - suffix]
        ) {
            suffix++
        }
        val middleHave = have.subList(prefix, have.size - suffix)
        val middleWant = want.subList(prefix, want.size - suffix)

        return when {
            middleHave.isEmpty() ->
                listOf(QueueEdit.Insert(position = if (prefix == have.size) 0 else prefix + 1, targetIndices = (prefix until prefix + middleWant.size).toList()))

            middleWant.isEmpty() -> listOf(QueueEdit.Remove(first = prefix + 1, count = middleHave.size))

            else -> moveOf(middleHave, middleWant, prefix, playing) ?: rebuild(have, want, playing)
        }
    }

    /** One block moved: the middle is the same two blocks in the other order, so either one can be the one that moves. */
    private fun moveOf(middleHave: List<String>, middleWant: List<String>, prefix: Int, playing: Int?): List<QueueEdit>? {
        if (middleHave.size != middleWant.size) return null
        val size = middleHave.size
        val split = (1 until size).firstOrNull { k ->
            middleHave.subList(k, size) + middleHave.subList(0, k) == middleWant
        } ?: return null
        // Blocks: first = [0, split), second = [split, size). Moving the first after the second is the same as the second before the first.
        val playingInFirst = playing != null && playing - prefix in 0 until split
        val playingInSecond = playing != null && playing - prefix in split until size
        val moveFirst = when {
            playingInFirst -> false
            playingInSecond -> true
            else -> split <= size - split
        }
        return if (moveFirst) {
            listOf(QueueEdit.Move(first = prefix + 1, count = split, before = prefix + size + 1))
        } else {
            listOf(QueueEdit.Move(first = prefix + split + 1, count = size - split, before = prefix + 1))
        }
    }

    /**
     * Everything but the song that plays is taken out and put back in the new order: what comes after it first, so that
     * "next" works soon, then what comes before.
     */
    private fun rebuild(have: List<String>, want: List<String>, playing: Int?): List<QueueEdit> {
        if (playing == null || playing !in have.indices) return listOf(QueueEdit.Reload)
        val anchor = want.indexOf(have[playing])
        if (anchor < 0) return listOf(QueueEdit.Reload)
        val edits = mutableListOf<QueueEdit>()
        val after = have.size - playing - 1
        if (after > 0) edits += QueueEdit.Remove(first = playing + 2, count = after)
        if (playing > 0) edits += QueueEdit.Remove(first = 1, count = playing)
        if (anchor + 1 < want.size) edits += QueueEdit.Insert(position = 0, targetIndices = (anchor + 1 until want.size).toList())
        if (anchor > 0) edits += QueueEdit.Insert(position = 1, targetIndices = (0 until anchor).toList())
        return edits
    }

    /** `a`, `b`, `a` become `a#1`, `b#1`, `a#2`. */
    private fun keysOf(ids: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return ids.map { id -> "$id#${seen.merge(id, 1, Int::plus)}" }
    }
}
