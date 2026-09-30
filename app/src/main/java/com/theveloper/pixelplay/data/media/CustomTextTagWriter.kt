package com.theveloper.pixelplay.data.media

import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v23Frame
import org.jaudiotagger.tag.id3.ID3v23Frames
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Frames
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.jaudiotagger.tag.wav.WavTag
import timber.log.Timber

/**
 * Writes an arbitrary user-text field to a JAudioTagger [Tag] - the write-side mirror of
 * [CustomTextTagReader]. Same per-container dispatch: Vorbis comments are a flat key/value store
 * that supports true multi-value fields (repeated entries under the same key), while ID3v2 TXXX
 * frames and MP4 freeform atoms identify the field by a description string carried *inside* the
 * frame and only ever hold one text value, so multiple logical values are joined with "; " -
 * the exact convention [CustomTextTagReader] already expects to split back apart. Extracted out
 * of what was originally ReplayGain-specific write logic once ARTISTS needed the identical
 * per-container dispatch, just multi-valued - see SongMetadataEditor's ReplayGain writer for the
 * single-value caller.
 */
object CustomTextTagWriter {

    private const val TAG = "CustomTextTagWriter"
    private const val MP4_FREEFORM_MEAN = "com.apple.iTunes"

    /** Replaces the field's value(s) entirely. An empty list removes the field. */
    fun writeAll(tag: Tag, flatKey: String, descriptionKey: String, values: List<String>) {
        val nonBlank = values.filter { it.isNotBlank() }
        when (tag) {
            is FlacTag -> writeAllVorbis(tag.vorbisCommentTag, flatKey, nonBlank)
            is VorbisCommentTag -> writeAllVorbis(tag, flatKey, nonBlank)
            is AbstractID3v2Tag -> tag.writeTxxx(descriptionKey, nonBlank)
            is WavTag -> (tag.getID3Tag() ?: ID3v24Tag().also(tag::setID3Tag)).writeTxxx(descriptionKey, nonBlank)
            is Mp4Tag -> {
                val fieldId = mp4FieldId(descriptionKey)
                tag.deleteField(fieldId)
                if (nonBlank.isNotEmpty()) {
                    tag.setField(
                        Mp4TagReverseDnsField(fieldId, MP4_FREEFORM_MEAN, descriptionKey, nonBlank.joinToString("; "))
                    )
                }
            }
            else -> Timber.tag(TAG).w("Custom text field write is not supported for tag type: ${tag::class.java.simpleName}")
        }
    }

    fun remove(tag: Tag, flatKey: String, descriptionKey: String) = writeAll(tag, flatKey, descriptionKey, emptyList())

    private fun writeAllVorbis(vorbis: VorbisCommentTag, key: String, values: List<String>) {
        runCatching { vorbis.deleteField(key) }
        values.forEach { value -> runCatching { vorbis.addField(key, value) } }
    }

    private fun AbstractID3v2Tag.writeTxxx(description: String, values: List<String>) {
        removeExistingTxxx(description)
        if (values.isEmpty()) return
        val frame = if (this is ID3v23Tag) {
            ID3v23Frame(ID3v23Frames.FRAME_ID_V3_USER_DEFINED_INFO)
        } else {
            ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO)
        }
        frame.body = FrameBodyTXXX().apply {
            setDescription(description)
            setText(values.joinToString("; "))
        }
        setField(frame)
    }

    private fun AbstractID3v2Tag.removeExistingTxxx(description: String) {
        val frameId = if (this is ID3v23Tag) {
            ID3v23Frames.FRAME_ID_V3_USER_DEFINED_INFO
        } else {
            ID3v24Frames.FRAME_ID_USER_DEFINED_INFO
        }
        val frames = getFields(frameId)
        val iterator = frames.listIterator()
        while (iterator.hasNext()) {
            val frame = iterator.next() as? AbstractID3v2Frame ?: continue
            val body = frame.body as? FrameBodyTXXX ?: continue
            if (body.description == description) {
                if (frames.size == 1) {
                    removeFrame(frameId)
                } else {
                    iterator.remove()
                }
            }
        }
    }

    private fun mp4FieldId(descriptionKey: String): String = "----:$MP4_FREEFORM_MEAN:$descriptionKey"
}
