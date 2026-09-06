package com.theveloper.pixelplay.data.download.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CloudDownloadQualityTest {

    @Test
    fun `MAX carries no target codec, container, bitrate or extension — D-02, literal copy`() {
        assertNull(CloudDownloadQuality.MAX.codec)
        assertNull(CloudDownloadQuality.MAX.container)
        assertNull(CloudDownloadQuality.MAX.bitrate)
        assertNull(CloudDownloadQuality.MAX.extension)
    }

    @Test
    fun `ALL has exactly one entry while D-02 keeps transcoded tiers parked`() {
        assertEquals(listOf(CloudDownloadQuality.MAX), CloudDownloadQuality.ALL)
    }

    @Test
    fun `fromCode resolves a known code back to its quality`() {
        assertEquals(CloudDownloadQuality.MAX, CloudDownloadQuality.fromCode(CloudDownloadQuality.MAX.code))
    }

    @Test
    fun `fromCode returns null for a code this build doesn't recognize`() {
        // Models a row written by a future version with a quality tier this build lacks.
        assertNull(CloudDownloadQuality.fromCode(code = 99))
    }
}
