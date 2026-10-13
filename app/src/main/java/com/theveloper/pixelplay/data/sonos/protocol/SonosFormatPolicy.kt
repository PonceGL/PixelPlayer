package com.theveloper.pixelplay.data.sonos.protocol

internal data class AudioFormat(
    val mimeType: String?,
    val sampleRateHz: Int?,
    val bitsPerSample: Int?,
)

internal enum class UnsupportedReason { SAMPLE_RATE_TOO_HIGH, BIT_DEPTH_TOO_HIGH, CODEC_NOT_SUPPORTED }

internal sealed interface FormatVerdict {
    data object Supported : FormatVerdict
    data class Unsupported(val reason: UnsupportedReason) : FormatVerdict

    /** Not enough information: the file is sent anyway and a silent skip is caught later. */
    data object Unknown : FormatVerdict
}

/** What a Sonos speaker can play from a local library, measured on a Move 2 and in Sonos' documentation. */
internal object SonosFormatPolicy {

    const val MAX_SAMPLE_RATE_HZ = 48_000
    private const val MAX_LOSSLESS_BITS = 24
    private const val MAX_PCM_CONTAINER_BITS = 16

    private val LOSSLESS = setOf("audio/flac", "audio/x-flac", "audio/alac")
    private val MP4_CONTAINER = setOf("audio/mp4", "audio/x-m4a", "audio/m4a")
    private val PCM_CONTAINER = setOf(
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "audio/aiff", "audio/x-aiff",
    )
    private val LOSSY = setOf("audio/mpeg", "audio/mp3", "audio/aac", "audio/aacp", "audio/mp4a-latm", "audio/vorbis")
    private val OPUS = setOf("audio/opus")

    fun evaluate(format: AudioFormat): FormatVerdict {
        val mime = format.mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val rate = format.sampleRateHz
        val bits = format.bitsPerSample
        if (rate != null && rate > MAX_SAMPLE_RATE_HZ) {
            return if (mime in OPUS) codecNotSupported() else sampleRateTooHigh(mime)
        }
        return when (mime) {
            in LOSSLESS -> losslessVerdict(rate, bits)
            in MP4_CONTAINER -> if (rate == null) FormatVerdict.Unknown else losslessVerdict(rate, bits)
            in PCM_CONTAINER -> pcmVerdict(rate, bits)
            in LOSSY -> FormatVerdict.Supported
            in OPUS -> codecNotSupported()
            else -> FormatVerdict.Unknown
        }
    }

    private fun sampleRateTooHigh(mime: String): FormatVerdict =
        if (mime in LOSSLESS || mime in MP4_CONTAINER || mime in PCM_CONTAINER || mime in LOSSY) {
            FormatVerdict.Unsupported(UnsupportedReason.SAMPLE_RATE_TOO_HIGH)
        } else {
            FormatVerdict.Unknown
        }

    private fun codecNotSupported() = FormatVerdict.Unsupported(UnsupportedReason.CODEC_NOT_SUPPORTED)

    private fun losslessVerdict(rate: Int?, bits: Int?): FormatVerdict = when {
        rate == null -> FormatVerdict.Unknown
        bits != null && bits > MAX_LOSSLESS_BITS -> FormatVerdict.Unsupported(UnsupportedReason.BIT_DEPTH_TOO_HIGH)
        else -> FormatVerdict.Supported
    }

    private fun pcmVerdict(rate: Int?, bits: Int?): FormatVerdict = when {
        rate == null || bits == null -> FormatVerdict.Unknown
        bits > MAX_PCM_CONTAINER_BITS -> FormatVerdict.Unsupported(UnsupportedReason.BIT_DEPTH_TOO_HIGH)
        else -> FormatVerdict.Supported
    }
}
