package com.theveloper.pixelplay.data.sonos.session

/** The changes a media3 player can be asked to make to its queue, on a plain list, with media3's meaning for the indexes. */
internal object QueueListEdits {

    fun <T> add(queue: List<T>, index: Int, items: List<T>): List<T> {
        val at = index.coerceIn(0, queue.size)
        return queue.take(at) + items + queue.drop(at)
    }

    /** Takes out the songs from [from] up to, not including, [to]. */
    fun <T> remove(queue: List<T>, from: Int, to: Int): List<T> {
        val start = from.coerceIn(0, queue.size)
        val end = to.coerceIn(start, queue.size)
        return queue.take(start) + queue.drop(end)
    }

    /** Moves the songs from [from] up to [to] so that the first of them ends at [newIndex] of the resulting queue. */
    fun <T> move(queue: List<T>, from: Int, to: Int, newIndex: Int): List<T> {
        val start = from.coerceIn(0, queue.size)
        val end = to.coerceIn(start, queue.size)
        val block = queue.subList(start, end)
        val rest = queue.take(start) + queue.drop(end)
        return add(rest, newIndex, block)
    }

    fun <T> replace(queue: List<T>, from: Int, to: Int, items: List<T>): List<T> {
        val start = from.coerceIn(0, queue.size)
        return add(remove(queue, from, to), start, items)
    }
}
