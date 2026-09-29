package com.theveloper.pixelplay.data.media

import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.asf.AsfTag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v23Frames
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Frames
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.jaudiotagger.tag.wav.WavTag

/**
 * Reads a single arbitrary user-text field from a JAudioTagger [Tag].
 *
 * Mirrors, in the read direction, the per-container dispatch that
 * [SongMetadataEditor] already uses to *write* a custom field (ReplayGain
 * today): same tag types, same per-format field identification rules.
 *
 * Vorbis comments and ASF content descriptors are flat key/value stores, so
 * one key name works for both. ID3v2 TXXX frames and MP4 freeform atoms
 * instead identify the field by a description/name string carried *inside*
 * the frame, so those two need their own lookup by that description.
 */
object CustomTextTagReader {

    private const val MP4_FREEFORM_MEAN = "com.apple.iTunes"

    // Multi-value convention for a single text frame/atom (ID3v2 TXXX, MP4
    // freeform): Picard joins values with "; " in these containers (confirmed
    // via real Picard-tagged files, for an analogous multi-value TXXX field -
    // no direct sample for TXXX:ARTISTS itself). Also splits on a literal NUL,
    // ID3v2.4's own multi-value text-frame separator, in case a differently
    // configured tagger uses that convention instead. Vorbis comments and ASF
    // attributes need neither - true multi-value fields, see readAll below.
    private val MULTI_VALUE_DELIMITER_REGEX = Regex("[;\u0000]\\s*")

    fun read(tag: Tag, flatKey: String, descriptionKey: String): String? =
        readAll(tag, flatKey, descriptionKey).firstOrNull()

    /** Like [read], but for fields that may carry more than one value (e.g. multiple credited artists). */
    fun readAll(tag: Tag, flatKey: String, descriptionKey: String): List<String> =
        when (tag) {
            // FlacTag implements Tag directly and WRAPS a VorbisCommentTag
            // rather than extending it - must unwrap before the flat-key
            // lookup below, or every FLAC file returns nothing here.
            is FlacTag -> readAllNonBlank(tag.vorbisCommentTag::getAll, flatKey)
            is VorbisCommentTag -> readAllNonBlank(tag::getAll, flatKey)
            is AsfTag -> readAllNonBlank(tag::getAll, flatKey)
            is AbstractID3v2Tag -> tag.readAllTxxxByDescription(descriptionKey)
            is WavTag -> tag.getID3Tag()?.readAllTxxxByDescription(descriptionKey) ?: emptyList()
            is Mp4Tag -> tag.getFirst("----:$MP4_FREEFORM_MEAN:$descriptionKey")
                ?.let(::splitMultiValue)
                ?: emptyList()
            else -> emptyList()
        }

    /**
     * Splits a value that may itself join more than one entry (ID3 TXXX/MP4
     * freeform convention) - a no-op (single-element list) for a value that
     * carries only one, e.g. a duo name that happens to contain none of the
     * delimiter characters.
     */
    fun splitMultiValue(text: String): List<String> =
        text.split(MULTI_VALUE_DELIMITER_REGEX).map { it.trim() }.filter { it.isNotBlank() }

    private fun readAllNonBlank(getAll: (String) -> List<String>, key: String): List<String> =
        runCatching { getAll(key) }.getOrDefault(emptyList()).filter { it.isNotBlank() }

    private fun AbstractID3v2Tag.readAllTxxxByDescription(description: String): List<String> {
        val frameId = if (this is ID3v23Tag) {
            ID3v23Frames.FRAME_ID_V3_USER_DEFINED_INFO
        } else {
            ID3v24Frames.FRAME_ID_USER_DEFINED_INFO
        }
        val text = getFields(frameId)
            .filterIsInstance<AbstractID3v2Frame>()
            .mapNotNull { it.body as? FrameBodyTXXX }
            .firstOrNull { it.description.equals(description, ignoreCase = true) }
            ?.text
            ?: return emptyList()
        return splitMultiValue(text)
    }
}
