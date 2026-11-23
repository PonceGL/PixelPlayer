package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class QueueListEditsTest {

    private fun ids(text: String) = text.map { it.toString() }
    private fun String.edit(block: (List<String>) -> List<String>) = block(ids(this)).joinToString("")

    @Test
    fun `adding puts the songs at the index`() {
        assertThat("abc".edit { QueueListEdits.add(it, 1, ids("XY")) }).isEqualTo("aXYbc")
        assertThat("abc".edit { QueueListEdits.add(it, 3, ids("X")) }).isEqualTo("abcX")
        assertThat("abc".edit { QueueListEdits.add(it, 0, ids("X")) }).isEqualTo("Xabc")
    }

    @Test
    fun `an index past the end adds at the end`() {
        assertThat("abc".edit { QueueListEdits.add(it, 99, ids("X")) }).isEqualTo("abcX")
    }

    @Test
    fun `removing takes the range out, and a range past the end is cut`() {
        assertThat("abcde".edit { QueueListEdits.remove(it, 1, 3) }).isEqualTo("ade")
        assertThat("abcde".edit { QueueListEdits.remove(it, 3, 99) }).isEqualTo("abc")
        assertThat("abcde".edit { QueueListEdits.remove(it, 2, 2) }).isEqualTo("abcde")
    }

    @Test
    fun `moving lands the first song of the block at the new index of the resulting list`() {
        assertThat("abcde".edit { QueueListEdits.move(it, 1, 2, 3) }).isEqualTo("acdbe")
        assertThat("abcde".edit { QueueListEdits.move(it, 3, 5, 0) }).isEqualTo("deabc")
        assertThat("abcde".edit { QueueListEdits.move(it, 0, 2, 3) }).isEqualTo("cdeab")
        assertThat("abcde".edit { QueueListEdits.move(it, 1, 3, 1) }).isEqualTo("abcde")
    }

    @Test
    fun `replacing is a removal and an addition at the same place`() {
        assertThat("abcde".edit { QueueListEdits.replace(it, 1, 4, ids("XY")) }).isEqualTo("aXYe")
        assertThat("abc".edit { QueueListEdits.replace(it, 3, 3, ids("X")) }).isEqualTo("abcX")
    }
}
