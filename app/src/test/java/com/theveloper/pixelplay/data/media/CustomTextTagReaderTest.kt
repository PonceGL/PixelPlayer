package com.theveloper.pixelplay.data.media

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.jaudiotagger.audio.wav.WavOptions
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.asf.AsfTag
import org.jaudiotagger.tag.asf.AsfTagTextField
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Frames
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.jaudiotagger.tag.wav.WavTag
import org.junit.Test

private const val FLAT_KEY = "DATE_ADDED"
private const val DESCRIPTION_KEY = "Date Added"
private const val VALUE = "2026-06-20 07:36:45"

class CustomTextTagReaderTest {

    @Test
    fun read_vorbisCommentTag_flatKeyLookup_returnsValue() {
        val tag = VorbisCommentTag()
        tag.setField(FLAT_KEY, VALUE)

        assertThat(CustomTextTagReader.read(tag, FLAT_KEY, DESCRIPTION_KEY)).isEqualTo(VALUE)
    }

    @Test
    fun read_vorbisCommentTag_missingField_returnsNull() {
        val tag = VorbisCommentTag()

        assertThat(CustomTextTagReader.read(tag, FLAT_KEY, DESCRIPTION_KEY)).isNull()
    }

    @Test
    fun read_asfTag_flatKeyLookup_returnsValue() {
        val tag = AsfTag()
        tag.setField(AsfTagTextField(FLAT_KEY, VALUE))

        assertThat(CustomTextTagReader.read(tag, FLAT_KEY, DESCRIPTION_KEY)).isEqualTo(VALUE)
    }

    @Test
    fun read_id3v2Tag_txxxByDescription_returnsValue() {
        val tag = ID3v24Tag()
        val frame = ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO)
        frame.body = FrameBodyTXXX().apply {
            setDescription(DESCRIPTION_KEY)
            setText(VALUE)
        }
        tag.setField(frame)

        assertThat(CustomTextTagReader.read(tag, FLAT_KEY, DESCRIPTION_KEY)).isEqualTo(VALUE)
    }

    @Test
    fun read_id3v2Tag_descriptionCaseInsensitive_returnsValue() {
        val tag = ID3v24Tag()
        val frame = ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO)
        frame.body = FrameBodyTXXX().apply {
            setDescription("date added")
            setText(VALUE)
        }
        tag.setField(frame)

        assertThat(CustomTextTagReader.read(tag, FLAT_KEY, DESCRIPTION_KEY)).isEqualTo(VALUE)
    }

    @Test
    fun read_id3v2Tag_differentTxxxDescription_returnsNull() {
        val tag = ID3v24Tag()
        val frame = ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO)
        frame.body = FrameBodyTXXX().apply {
            setDescription("Some Other Field")
            setText(VALUE)
        }
        tag.setField(frame)

        assertThat(CustomTextTagReader.read(tag, FLAT_KEY, DESCRIPTION_KEY)).isNull()
    }

    @Test
    fun read_id3v2Tag_noTxxxFrames_returnsNull() {
        val tag = ID3v24Tag()

        assertThat(CustomTextTagReader.read(tag, FLAT_KEY, DESCRIPTION_KEY)).isNull()
    }

    @Test
    fun read_wavTagWithId3_txxxByDescription_returnsValue() {
        val id3Tag = ID3v24Tag()
        val frame = ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO)
        frame.body = FrameBodyTXXX().apply {
            setDescription(DESCRIPTION_KEY)
            setText(VALUE)
        }
        id3Tag.setField(frame)
        val wavTag = WavTag(WavOptions.READ_ID3_ONLY).apply { setID3Tag(id3Tag) }

        assertThat(CustomTextTagReader.read(wavTag, FLAT_KEY, DESCRIPTION_KEY)).isEqualTo(VALUE)
    }

    @Test
    fun read_wavTagWithoutId3_returnsNull() {
        val wavTag = WavTag(WavOptions.READ_ID3_ONLY)

        assertThat(CustomTextTagReader.read(wavTag, FLAT_KEY, DESCRIPTION_KEY)).isNull()
    }

    @Test
    fun read_mp4Tag_appleItunesFreeformAtom_returnsValue() {
        val tag = Mp4Tag()
        tag.setField(Mp4TagReverseDnsField("----:com.apple.iTunes:$DESCRIPTION_KEY", "com.apple.iTunes", DESCRIPTION_KEY, VALUE))

        assertThat(CustomTextTagReader.read(tag, FLAT_KEY, DESCRIPTION_KEY)).isEqualTo(VALUE)
    }

    @Test
    fun read_mp4Tag_missingFreeformAtom_returnsNull() {
        val tag = Mp4Tag()

        assertThat(CustomTextTagReader.read(tag, FLAT_KEY, DESCRIPTION_KEY)).isNull()
    }

    @Test
    fun read_unsupportedTagType_returnsNull() {
        val tag = mockk<Tag>(relaxed = true)

        assertThat(CustomTextTagReader.read(tag, FLAT_KEY, DESCRIPTION_KEY)).isNull()
    }

    // Regression: JAudioTagger represents FLAC as FlacTag, which WRAPS a
    // VorbisCommentTag rather than extending it (FlacTag implements Tag
    // directly). A plain `is VorbisCommentTag` check never matches for FLAC,
    // silently returning null for every FLAC file on the JAudioTagger
    // fallback path - found while investigating real Picard-tagged files.
    @Test
    fun read_flacTag_unwrapsInternalVorbisCommentTag_returnsValue() {
        val flacTag = FlacTag()
        flacTag.vorbisCommentTag.setField(FLAT_KEY, VALUE)

        assertThat(CustomTextTagReader.read(flacTag, FLAT_KEY, DESCRIPTION_KEY)).isEqualTo(VALUE)
    }

    @Test
    fun readAll_vorbisCommentTag_flatKeyLookup_returnsAllValues() {
        val tag = VorbisCommentTag()
        tag.setField(FLAT_KEY, "Belinda")
        tag.addField(tag.createField(FLAT_KEY, "Natanael Cano"))

        assertThat(CustomTextTagReader.readAll(tag, FLAT_KEY, DESCRIPTION_KEY))
            .containsExactly("Belinda", "Natanael Cano")
            .inOrder()
    }

    @Test
    fun readAll_flacTag_unwrapsInternalVorbisCommentTag_returnsAllValues() {
        val flacTag = FlacTag()
        flacTag.vorbisCommentTag.setField(FLAT_KEY, "Belinda")
        flacTag.vorbisCommentTag.addField(flacTag.vorbisCommentTag.createField(FLAT_KEY, "Natanael Cano"))

        assertThat(CustomTextTagReader.readAll(flacTag, FLAT_KEY, DESCRIPTION_KEY))
            .containsExactly("Belinda", "Natanael Cano")
            .inOrder()
    }

    @Test
    fun readAll_vorbisCommentTag_missingField_returnsEmptyList() {
        val tag = VorbisCommentTag()

        assertThat(CustomTextTagReader.readAll(tag, FLAT_KEY, DESCRIPTION_KEY)).isEmpty()
    }

    @Test
    fun readAll_id3v2Tag_txxxSemicolonJoined_returnsSplitValues() {
        val tag = ID3v24Tag()
        val frame = ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO)
        frame.body = FrameBodyTXXX().apply {
            setDescription(DESCRIPTION_KEY)
            setText("Belinda; Natanael Cano")
        }
        tag.setField(frame)

        assertThat(CustomTextTagReader.readAll(tag, FLAT_KEY, DESCRIPTION_KEY))
            .containsExactly("Belinda", "Natanael Cano")
            .inOrder()
    }

    @Test
    fun readAll_id3v2Tag_singleValueNoSemicolon_returnsSingleElementList() {
        val tag = ID3v24Tag()
        val frame = ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO)
        frame.body = FrameBodyTXXX().apply {
            setDescription(DESCRIPTION_KEY)
            setText("Beast in Black")
        }
        tag.setField(frame)

        assertThat(CustomTextTagReader.readAll(tag, FLAT_KEY, DESCRIPTION_KEY))
            .containsExactly("Beast in Black")
    }

    @Test
    fun readAll_id3v2Tag_missingFrame_returnsEmptyList() {
        val tag = ID3v24Tag()

        assertThat(CustomTextTagReader.readAll(tag, FLAT_KEY, DESCRIPTION_KEY)).isEmpty()
    }

    @Test
    fun readAll_asfTag_flatKeyLookup_returnsAllValues() {
        val tag = AsfTag()
        tag.setField(AsfTagTextField(FLAT_KEY, "Belinda"))
        tag.addField(AsfTagTextField(FLAT_KEY, "Natanael Cano"))

        assertThat(CustomTextTagReader.readAll(tag, FLAT_KEY, DESCRIPTION_KEY))
            .containsExactly("Belinda", "Natanael Cano")
            .inOrder()
    }

    @Test
    fun readAll_mp4Tag_freeformSemicolonJoined_returnsSplitValues() {
        val tag = Mp4Tag()
        tag.setField(
            Mp4TagReverseDnsField(
                "----:com.apple.iTunes:$DESCRIPTION_KEY",
                "com.apple.iTunes",
                DESCRIPTION_KEY,
                "Belinda; Natanael Cano"
            )
        )

        assertThat(CustomTextTagReader.readAll(tag, FLAT_KEY, DESCRIPTION_KEY))
            .containsExactly("Belinda", "Natanael Cano")
            .inOrder()
    }

    @Test
    fun readAll_unsupportedTagType_returnsEmptyList() {
        val tag = mockk<Tag>(relaxed = true)

        assertThat(CustomTextTagReader.readAll(tag, FLAT_KEY, DESCRIPTION_KEY)).isEmpty()
    }
}
