package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.random.Random
import org.junit.jupiter.api.Test

class QueueEditPlannerTest {

    private fun ids(text: String) = text.map { it.toString() }

    /** What the speaker would do with each edit, as measured on a Move. */
    private fun apply(queue: List<String>, edits: List<QueueEdit>, target: List<String>): List<String> {
        var list = queue
        for (edit in edits) {
            list = when (edit) {
                is QueueEdit.Remove -> {
                    require(edit.first >= 1 && edit.first - 1 + edit.count <= list.size) { "remove out of range: $edit on $list" }
                    list.take(edit.first - 1) + list.drop(edit.first - 1 + edit.count)
                }
                is QueueEdit.Move -> {
                    require(edit.first >= 1 && edit.first - 1 + edit.count <= list.size) { "move out of range: $edit on $list" }
                    require(edit.before in 1..list.size + 1) { "move target out of range: $edit on $list" }
                    val block = list.subList(edit.first - 1, edit.first - 1 + edit.count)
                    val lastOfBlock = edit.first - 1 + edit.count
                    when {
                        edit.before - 1 <= edit.first - 1 -> list.take(edit.before - 1) + block + list.subList(edit.before - 1, edit.first - 1) + list.drop(lastOfBlock)
                        edit.before - 1 >= lastOfBlock -> list.take(edit.first - 1) + list.subList(lastOfBlock, edit.before - 1) + block + list.drop(edit.before - 1)
                        else -> list
                    }
                }
                is QueueEdit.Insert -> {
                    val at = if (edit.position == 0) list.size else edit.position - 1
                    require(at in 0..list.size) { "insert out of range: $edit on $list" }
                    list.take(at) + edit.targetIndices.map { target[it] } + list.drop(at)
                }
                QueueEdit.Reload -> target
            }
        }
        return list
    }

    private fun check(current: String, target: String, playing: Int?): List<QueueEdit> {
        val plan = QueueEditPlanner.plan(ids(current), ids(target), playing)
        assertThat(apply(ids(current), plan, ids(target))).isEqualTo(ids(target))
        return plan
    }

    @Test
    fun `the same queue needs nothing`() {
        assertThat(check("abcd", "abcd", playing = 1)).isEmpty()
    }

    @Test
    fun `songs added at the end are one insertion at the end`() {
        val plan = check("abc", "abcde", playing = 1)
        assertThat(plan).containsExactly(QueueEdit.Insert(position = 0, targetIndices = listOf(3, 4)))
    }

    @Test
    fun `a song added right after the playing one is one insertion there`() {
        val plan = check("abc", "abXc".replace('X', 'x'), playing = 1)
        assertThat(plan).containsExactly(QueueEdit.Insert(position = 3, targetIndices = listOf(2)))
    }

    @Test
    fun `a song removed is one removal`() {
        assertThat(check("abcd", "acd", playing = 0)).containsExactly(QueueEdit.Remove(first = 2, count = 1))
        assertThat(check("abcd", "ab", playing = 0)).containsExactly(QueueEdit.Remove(first = 3, count = 2))
    }

    @Test
    fun `the playing song removed is a removal too, the speaker goes on to the next`() {
        assertThat(check("abcd", "acd", playing = 1)).containsExactly(QueueEdit.Remove(first = 2, count = 1))
    }

    @Test
    fun `an emptied queue is one removal of everything`() {
        assertThat(check("abc", "", playing = 1)).containsExactly(QueueEdit.Remove(first = 1, count = 3))
    }

    @Test
    fun `an empty speaker only needs the songs`() {
        assertThat(check("", "abc", playing = null)).containsExactly(QueueEdit.Insert(position = 0, targetIndices = listOf(0, 1, 2)))
    }

    @Test
    fun `one song moved is one move, and the block that does not hold the playing song is the one that moves`() {
        // a b [c] d e -> a d b [c] e : the song d goes before b, the playing one (c) is not touched
        val plan = check("abcde", "adbce", playing = 2)
        assertThat(plan).hasSize(1)
        val move = plan.single() as QueueEdit.Move
        assertThat(move.count).isEqualTo(1)
        assertThat(move.first).isEqualTo(4)
        assertThat(move.before).isEqualTo(2)
    }

    @Test
    fun `a song moved to the very end uses the length plus one`() {
        val plan = check("abcd", "bcda", playing = 2)
        assertThat(plan).containsExactly(QueueEdit.Move(first = 1, count = 1, before = 5))
    }

    @Test
    fun `a shuffle is rebuilt around the playing song without removing it`() {
        val plan = check("abcdef", "fdcbea".replace('c', 'c'), playing = 2)
        assertThat(plan.none { it is QueueEdit.Reload }).isTrue()
        // what is after the playing song goes first, so that "next" works soon
        val inserts = plan.filterIsInstance<QueueEdit.Insert>()
        assertThat(inserts).hasSize(2)
        assertThat(inserts[0].position).isEqualTo(0)
        assertThat(inserts[1].position).isEqualTo(1)
    }

    @Test
    fun `when the playing song is not in the new queue and the change is big, the queue is loaded again`() {
        val plan = check("abcd", "wxyz", playing = 1)
        assertThat(plan).containsExactly(QueueEdit.Reload)
    }

    @Test
    fun `with no known playing song a big change loads again`() {
        assertThat(check("abcd", "dcba", playing = null)).containsExactly(QueueEdit.Reload)
    }

    @Test
    fun `the same song twice is told apart by its place`() {
        check("abab", "abbab", playing = 0)
        check("aab", "aaab", playing = 1)
        check("abca", "acba", playing = 3)
    }

    @Test
    fun `any change of any queue ends in the new queue, and the playing song is only removed when the new queue has not got it`() {
        val random = Random(20261016)
        repeat(600) { round ->
            val alphabet = "abcdefgh"
            val size = random.nextInt(0, 9)
            val current = List(size) { alphabet[random.nextInt(if (round % 3 == 0) 3 else alphabet.length)].toString() }
            val target = when (random.nextInt(4)) {
                0 -> current.shuffled(random)
                1 -> current.filterIndexed { i, _ -> random.nextInt(3) != 0 }
                2 -> current + List(random.nextInt(0, 4)) { alphabet[random.nextInt(alphabet.length)].toString() }
                else -> List(random.nextInt(0, 9)) { alphabet[random.nextInt(alphabet.length)].toString() }
            }
            val playing = if (current.isEmpty() || random.nextInt(5) == 0) null else random.nextInt(current.size)

            val plan = QueueEditPlanner.plan(current, target, playing)

            assertWithMessage("current=$current target=$target playing=$playing plan=$plan")
                .that(apply(current, plan, target)).isEqualTo(target)
            if (playing != null && plan.none { it is QueueEdit.Reload } && target.isNotEmpty()) {
                // The playing song (this very entry) survives every removal unless the plan removes it on purpose:
                // simulate with markers to follow it.
                var marked = current.mapIndexed { i, id -> if (i == playing) "$id*" else id }
                var alive = true
                for (edit in plan) {
                    val before = marked
                    marked = apply(marked, listOf(edit), target.map { it })
                    if (before.any { it.endsWith("*") } && marked.none { it.endsWith("*") }) alive = false
                }
                if (!alive) {
                    val key = current[playing]
                    val occurrence = current.take(playing + 1).count { it == key }
                    assertWithMessage("the playing song was removed although the new queue has it: current=$current target=$target playing=$playing plan=$plan")
                        .that(target.count { it == key } >= occurrence).isFalse()
                }
            }
        }
    }
}
