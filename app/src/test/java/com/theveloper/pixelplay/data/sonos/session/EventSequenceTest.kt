package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EventSequenceTest {

    private fun EventSequence.feed(vararg seqs: Long): List<SequenceCheck> {
        var current = this
        return seqs.map { seq ->
            val (next, check) = current.accept(seq)
            current = next
            check
        }
    }

    @Test
    fun `consecutive numbers are in order`() {
        assertThat(EventSequence().feed(0, 1, 2)).containsExactly(
            SequenceCheck.InOrder, SequenceCheck.InOrder, SequenceCheck.InOrder,
        )
    }

    @Test
    fun `a skipped number reveals lost events`() {
        assertThat(EventSequence().feed(0, 1, 3)).containsExactly(
            SequenceCheck.InOrder, SequenceCheck.InOrder, SequenceCheck.Gap,
        ).inOrder()
    }

    @Test
    fun `numbering continues after a gap`() {
        assertThat(EventSequence().feed(0, 3, 4)).containsExactly(
            SequenceCheck.InOrder, SequenceCheck.Gap, SequenceCheck.InOrder,
        ).inOrder()
    }

    @Test
    fun `zero after other numbers means the subscription restarted`() {
        assertThat(EventSequence().feed(0, 1, 5, 0)).containsExactly(
            SequenceCheck.InOrder, SequenceCheck.InOrder, SequenceCheck.Gap, SequenceCheck.Restarted,
        ).inOrder()
    }

    @Test
    fun `a first event that is not zero means earlier events were missed`() {
        assertThat(EventSequence().feed(4)).containsExactly(SequenceCheck.Gap)
    }

    @Test
    fun `a repeated or older number is treated as a gap so the state gets reread`() {
        assertThat(EventSequence().feed(0, 1, 2, 2, 1)).containsExactly(
            SequenceCheck.InOrder, SequenceCheck.InOrder, SequenceCheck.InOrder, SequenceCheck.Gap, SequenceCheck.Gap,
        ).inOrder()
    }
}
