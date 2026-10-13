package com.theveloper.pixelplay.data.sonos.server

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HttpRangeTest {

    private fun resolve(header: String?, size: Long = 1000) = HttpRange.resolve(header, size)

    @Test
    fun `no range header serves the whole file`() {
        assertThat(resolve(null)).isEqualTo(RangeResult.Full)
        assertThat(resolve("")).isEqualTo(RangeResult.Full)
        assertThat(resolve("   ")).isEqualTo(RangeResult.Full)
    }

    @Test
    fun `a bounded range is returned as asked`() {
        val result = resolve("bytes=0-99")

        assertThat(result).isEqualTo(RangeResult.Partial(0, 99))
        assertThat((result as RangeResult.Partial).length).isEqualTo(100)
    }

    @Test
    fun `a range open at the end runs to the last byte`() {
        assertThat(resolve("bytes=0-")).isEqualTo(RangeResult.Partial(0, 999))
        assertThat(resolve("bytes=900-")).isEqualTo(RangeResult.Partial(900, 999))
    }

    @Test
    fun `a suffix range is the last bytes`() {
        assertThat(resolve("bytes=-500")).isEqualTo(RangeResult.Partial(500, 999))
        assertThat(resolve("bytes=-1")).isEqualTo(RangeResult.Partial(999, 999))
    }

    @Test
    fun `a suffix longer than the file is the whole file as a partial answer`() {
        assertThat(resolve("bytes=-5000")).isEqualTo(RangeResult.Partial(0, 999))
    }

    @Test
    fun `an end past the last byte is clipped`() {
        assertThat(resolve("bytes=900-5000")).isEqualTo(RangeResult.Partial(900, 999))
    }

    @Test
    fun `a start at or past the end is not satisfiable`() {
        assertThat(resolve("bytes=1000-")).isEqualTo(RangeResult.NotSatisfiable)
        assertThat(resolve("bytes=1000-1500")).isEqualTo(RangeResult.NotSatisfiable)
        assertThat(resolve("bytes=99999999999-")).isEqualTo(RangeResult.NotSatisfiable)
    }

    @Test
    fun `an empty suffix is not satisfiable`() {
        assertThat(resolve("bytes=-0")).isEqualTo(RangeResult.NotSatisfiable)
    }

    @Test
    fun `an inverted range is invalid and ignored as the standard asks`() {
        assertThat(resolve("bytes=100-50")).isEqualTo(RangeResult.Full)
    }

    @Test
    fun `a single byte range works`() {
        assertThat(resolve("bytes=0-0")).isEqualTo(RangeResult.Partial(0, 0))
        assertThat(resolve("bytes=999-999")).isEqualTo(RangeResult.Partial(999, 999))
    }

    @Test
    fun `several ranges are answered with the first one`() {
        assertThat(resolve("bytes=0-9,20-29,-5")).isEqualTo(RangeResult.Partial(0, 9))
    }

    @Test
    fun `the unit is case insensitive and spaces are tolerated`() {
        assertThat(resolve("Bytes=0-5")).isEqualTo(RangeResult.Partial(0, 5))
        assertThat(resolve("BYTES=0-5")).isEqualTo(RangeResult.Partial(0, 5))
        assertThat(resolve("bytes = 0-5")).isEqualTo(RangeResult.Partial(0, 5))
        assertThat(resolve("  bytes=0-5  ")).isEqualTo(RangeResult.Partial(0, 5))
    }

    @Test
    fun `garbage is ignored and the whole file is served`() {
        val garbage = listOf(
            "bytes=abc",
            "items=0-5",
            "bytes=",
            "bytes=-",
            "bytes=1-2-3",
            "bytes=--5",
            "bytes=-5-",
            "bytes=a-b",
            "bytes=0-5x",
            "0-5",
            "bytes",
            "\u0000\u0001",
            "bytes=0-99999999999999999999",
            "bytes=99999999999999999999-",
        )

        for (header in garbage) {
            assertThat(resolve(header)).isEqualTo(RangeResult.Full)
        }
    }

    @Test
    fun `an unknown size always serves the whole file`() {
        assertThat(resolve("bytes=0-99", size = -1)).isEqualTo(RangeResult.Full)
    }

    @Test
    fun `an empty file cannot satisfy a range but still serves without one`() {
        assertThat(resolve("bytes=0-99", size = 0)).isEqualTo(RangeResult.NotSatisfiable)
        assertThat(resolve(null, size = 0)).isEqualTo(RangeResult.Full)
        assertThat(resolve("garbage", size = 0)).isEqualTo(RangeResult.Full)
    }

    @Test
    fun `huge offsets in a very large file stay exact`() {
        val size = 6_000_000_000L

        assertThat(HttpRange.resolve("bytes=5000000000-", size)).isEqualTo(RangeResult.Partial(5_000_000_000L, size - 1))
    }
}
