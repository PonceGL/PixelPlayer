package com.theveloper.pixelplay.data.download.jellyfin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatchIdsByUrlBudgetTest {

    private val guid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890" // 36 chars, like a real Jellyfin id

    @Test
    fun `an empty list produces no batches`() {
        val batches = batchIdsByUrlBudget(emptyList(), baseUrlOverheadBytes = 100)

        assertTrue(batches.isEmpty())
    }

    @Test
    fun `a small list fits in a single batch`() {
        val ids = List(5) { guid }

        val batches = batchIdsByUrlBudget(ids, baseUrlOverheadBytes = 100)

        assertEquals(1, batches.size)
        assertEquals(5, batches.first().size)
    }

    @Test
    fun `a large list splits into multiple batches, each within the byte budget`() {
        // 36 + 1 (comma) = 37 bytes/id. With a 4000-byte budget and 100 bytes of overhead,
        // ~105 ids fit per batch — 300 ids should split into at least 3 batches.
        val ids = List(300) { guid }

        val batches = batchIdsByUrlBudget(ids, baseUrlOverheadBytes = 100, maxUrlBytes = 4_000)

        assertTrue(batches.size >= 3, "expected several batches, got ${batches.size}")
        batches.forEach { batch ->
            val batchBytes = 100 + batch.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
            assertTrue(batchBytes <= 4_000, "a batch exceeded the byte budget: $batchBytes")
        }
    }

    @Test
    fun `every id appears exactly once across all batches — none lost, none duplicated`() {
        val ids = (1..250).map { "id-$it" }

        val batches = batchIdsByUrlBudget(ids, baseUrlOverheadBytes = 100, maxUrlBytes = 500)

        assertEquals(ids, batches.flatten())
    }

    @Test
    fun `a single id larger than the whole budget still gets its own batch, not dropped`() {
        val hugeId = "x".repeat(10_000)

        val batches = batchIdsByUrlBudget(listOf(hugeId), baseUrlOverheadBytes = 100, maxUrlBytes = 4_000)

        assertEquals(listOf(listOf(hugeId)), batches)
    }
}
