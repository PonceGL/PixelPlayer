package com.theveloper.pixelplay.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class UpcomingQueueOrderTest {

    private val ids = ('a'..'j').map { it.toString() }

    private fun shuffled(queue: List<String>, current: Int, seed: Int = 7) =
        UpcomingQueueOrder.shuffled(queue, current, Random(seed))

    private fun restored(queue: List<String>, current: Int, original: List<String>) =
        UpcomingQueueOrder.restored(queue, current, original) { it }

    @Test
    fun `shuffling keeps the songs up to the one that plays exactly where they are`() {
        val result = shuffled(ids, current = 3)

        assertEquals(ids.take(4), result.take(4))
    }

    @Test
    fun `shuffling mixes only what comes after and loses and repeats nothing`() {
        val result = shuffled(ids, current = 3)

        assertEquals(ids.drop(4).sorted(), result.drop(4).sorted())
        assertNotEquals(ids.drop(4), result.drop(4))
    }

    @Test
    fun `shuffling with nothing after the playing song changes nothing`() {
        assertEquals(ids, shuffled(ids, current = ids.lastIndex))
    }

    @Test
    fun `shuffling a queue with no playing song known leaves it as it is`() {
        assertEquals(ids, shuffled(ids, current = -1))
        assertEquals(ids, shuffled(ids, current = ids.size))
    }

    @Test
    fun `restoring leaves the played songs and the one that plays where they are`() {
        val queue = listOf("c", "a", "e", "b", "d")

        val result = restored(queue, current = 1, original = listOf("a", "b", "c", "d", "e"))

        assertEquals(listOf("c", "a"), result.take(2))
    }

    @Test
    fun `restoring puts what comes after back in its original order`() {
        val queue = listOf("c", "a", "e", "b", "d")

        val result = restored(queue, current = 1, original = listOf("a", "b", "c", "d", "e"))

        assertEquals(listOf("c", "a", "b", "d", "e"), result)
    }

    @Test
    fun `songs added while shuffled stay right after the playing song, in the order they have`() {
        val queue = listOf("c", "a", "x", "e", "b", "y", "d")

        val result = restored(queue, current = 1, original = listOf("a", "b", "c", "d", "e"))

        assertEquals(listOf("c", "a", "x", "y", "b", "d", "e"), result)
    }

    @Test
    fun `a song taken out while shuffled does not come back`() {
        val queue = listOf("c", "a", "e", "d")

        val result = restored(queue, current = 1, original = listOf("a", "b", "c", "d", "e"))

        assertEquals(listOf("c", "a", "d", "e"), result)
    }

    @Test
    fun `the same song twice keeps both copies`() {
        val queue = listOf("a", "b", "c", "a", "b")

        val result = restored(queue, current = 0, original = listOf("a", "b", "a", "b", "c"))

        assertEquals(listOf("a", "b", "a", "b", "c"), result)
    }

    @Test
    fun `restoring with nothing after the playing song changes nothing`() {
        val queue = listOf("b", "a")

        assertEquals(queue, restored(queue, current = 1, original = listOf("a", "b")))
    }

    @Test
    fun `restoring with no playing song known leaves the queue as it is`() {
        val queue = listOf("b", "a")

        assertEquals(queue, restored(queue, current = -1, original = listOf("a", "b")))
    }

    @Test
    fun `restoring after a shuffle gives back the order the queue had`() {
        val shuffledQueue = shuffled(ids, current = 2)

        assertEquals(ids, restored(shuffledQueue, current = 2, original = ids))
    }
}
