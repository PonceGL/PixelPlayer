package com.theveloper.pixelplay.utils

import kotlin.random.Random

/**
 * The shuffle of a queue touches only what has not played yet: the songs up to the one that plays stay exactly where
 * they are, so "previous" still goes to the song that was heard before, whether the shuffle is on or off.
 */
object UpcomingQueueOrder {

    /**
     * [queue] with the songs after [currentIndex] in a random order (every song once). When [currentIndex] is not a place
     * of the queue, nothing is known to be playing and the queue is returned as it is.
     */
    fun <T> shuffled(queue: List<T>, currentIndex: Int, random: Random = Random.Default): List<T> {
        if (currentIndex !in queue.indices) return queue.toList()
        return queue.take(currentIndex + 1) + queue.drop(currentIndex + 1).shuffled(random)
    }

    /**
     * [queue] with the songs after [currentIndex] back in the order they have in [original] (the queue as it was before the
     * shuffle). Songs that are not in [original] (added while it was shuffled) go right after the playing song, in the
     * order they have; songs that were taken out stay out. A song is told apart from another copy of itself by its place
     * among its copies.
     */
    fun <T> restored(queue: List<T>, currentIndex: Int, original: List<T>, idOf: (T) -> String): List<T> {
        if (currentIndex !in queue.indices) return queue.toList()

        // Where each copy of a song was in the original. The songs up to the playing one take theirs first.
        val places = HashMap<String, ArrayDeque<Int>>()
        original.forEachIndexed { place, song -> places.getOrPut(idOf(song)) { ArrayDeque() }.addLast(place) }
        val head = queue.take(currentIndex + 1)
        head.forEach { song -> places[idOf(song)]?.removeFirstOrNull() }

        val added = mutableListOf<T>()
        val known = mutableListOf<Pair<Int, T>>()
        for (song in queue.drop(currentIndex + 1)) {
            val place = places[idOf(song)]?.removeFirstOrNull()
            if (place == null) added += song else known += place to song
        }
        return head + added + known.sortedBy { it.first }.map { it.second }
    }
}
