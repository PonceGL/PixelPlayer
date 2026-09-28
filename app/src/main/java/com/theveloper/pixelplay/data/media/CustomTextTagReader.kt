package com.theveloper.pixelplay.data.media

import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.asf.AsfTag
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

    fun read(tag: Tag, flatKey: String, descriptionKey: String): String? =
        when (tag) {
            is VorbisCommentTag -> tag.getFirst(flatKey)?.takeIf { it.isNotBlank() }
            is AsfTag -> tag.getFirst(flatKey)?.takeIf { it.isNotBlank() }
            is AbstractID3v2Tag -> tag.readTxxxByDescription(descriptionKey)
            is WavTag -> tag.getID3Tag()?.readTxxxByDescription(descriptionKey)
            is Mp4Tag -> tag.getFirst("----:$MP4_FREEFORM_MEAN:$descriptionKey")?.takeIf { it.isNotBlank() }
            else -> null
        }

    private fun AbstractID3v2Tag.readTxxxByDescription(description: String): String? {
        val frameId = if (this is ID3v23Tag) {
            ID3v23Frames.FRAME_ID_V3_USER_DEFINED_INFO
        } else {
            ID3v24Frames.FRAME_ID_USER_DEFINED_INFO
        }
        return getFields(frameId)
            .filterIsInstance<AbstractID3v2Frame>()
            .mapNotNull { it.body as? FrameBodyTXXX }
            .firstOrNull { it.description.equals(description, ignoreCase = true) }
            ?.text
            ?.takeIf { it.isNotBlank() }
    }
}
