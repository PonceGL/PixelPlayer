package com.theveloper.pixelplay.data.sonos.server

internal sealed interface RangeResult {

    /** Serve the whole file with status 200. */
    data object Full : RangeResult

    /** Serve bytes [start]..[endInclusive] with status 206. */
    data class Partial(val start: Long, val endInclusive: Long) : RangeResult {
        val length: Long get() = endInclusive - start + 1
    }

    /** Answer 416. */
    data object NotSatisfiable : RangeResult
}

internal object HttpRange {

    private val BYTES_RANGE = Regex("""^\s*bytes\s*=\s*(.*?)\s*$""", RegexOption.IGNORE_CASE)
    private val SPEC = Regex("""^(\d*)-(\d*)$""")

    /**
     * Reads a `Range` header against a file of [size] bytes. What cannot be understood is ignored, as
     * the standard asks, so the speaker gets the whole file instead of an error. Several ranges are
     * answered with the first one.
     */
    fun resolve(header: String?, size: Long): RangeResult {
        if (header == null || size < 0) return RangeResult.Full
        val spec = BYTES_RANGE.matchEntire(header)?.groupValues?.get(1)?.substringBefore(',')?.trim()
            ?: return RangeResult.Full
        val match = SPEC.matchEntire(spec) ?: return RangeResult.Full
        val (first, last) = match.destructured
        if (first.isEmpty() && last.isEmpty()) return RangeResult.Full
        return if (first.isEmpty()) suffix(last, size) else bounded(first, last, size)
    }

    private fun suffix(length: String, size: Long): RangeResult {
        val count = length.toLongOrNull() ?: return RangeResult.Full
        if (count == 0L || size == 0L) return RangeResult.NotSatisfiable
        return RangeResult.Partial(start = (size - count).coerceAtLeast(0), endInclusive = size - 1)
    }

    private fun bounded(first: String, last: String, size: Long): RangeResult {
        val start = first.toLongOrNull() ?: return RangeResult.Full
        val end = if (last.isEmpty()) Long.MAX_VALUE else last.toLongOrNull() ?: return RangeResult.Full
        if (end < start) return RangeResult.Full
        if (start >= size) return RangeResult.NotSatisfiable
        return RangeResult.Partial(start, minOf(end, size - 1))
    }
}
