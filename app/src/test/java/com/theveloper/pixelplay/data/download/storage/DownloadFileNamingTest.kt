package com.theveloper.pixelplay.data.download.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadFileNamingTest {

    @Test
    fun `a safe guid stays readable and only gains a suffix`() {
        val result = sanitizeRemoteIdForFilename("a1b2c3d4-e5f6-7890-abcd-ef1234567890")

        assertTrue(result.startsWith("a1b2c3d4-e5f6-7890-abcd-ef1234567890_"))
    }

    @Test
    fun `characters outside the safe set become underscores`() {
        val result = sanitizeRemoteIdForFilename("a/b:c*d?e")

        assertTrue(result.startsWith("a_b_c_d_e_"))
    }

    @Test
    fun `is deterministic for the same input`() {
        val first = sanitizeRemoteIdForFilename("same-id")
        val second = sanitizeRemoteIdForFilename("same-id")

        assertEquals(first, second)
    }

    @Test
    fun `two ids that sanitize to the same body still produce different names`() {
        // Both collapse to "a_b" once the separator is replaced — the exact motivating
        // case from PLAN.md §F1.5, case borde 3.
        val first = sanitizeRemoteIdForFilename("a/b")
        val second = sanitizeRemoteIdForFilename("a:b")

        assertNotEquals(first, second)
    }

    @Test
    fun `the result never exceeds the filesystem's filename byte limit`() {
        val veryLongId = "x".repeat(1000)

        val result = sanitizeRemoteIdForFilename(veryLongId)

        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 255)
    }

    @Test
    fun `two long ids differing only past the truncation point still get different names`() {
        val base = "x".repeat(300)
        val first = sanitizeRemoteIdForFilename(base + "AAAA")
        val second = sanitizeRemoteIdForFilename(base + "BBBB")

        // The truncated bodies would be identical; only the suffix — derived from the full
        // original id — can tell them apart.
        assertNotEquals(first, second)
    }

    @Test
    fun `an empty remoteId still produces a non-empty, valid name`() {
        val result = sanitizeRemoteIdForFilename("")

        assertTrue(result.isNotEmpty())
        assertTrue(result.matches(Regex("[A-Za-z0-9._-]+")))
    }
}
