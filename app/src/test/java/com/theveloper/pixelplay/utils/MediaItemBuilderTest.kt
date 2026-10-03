package com.theveloper.pixelplay.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaItemBuilderTest {

    @Test
    fun shouldPreferDirectLocalFileUri_prefersDirectFileUriForLocalM4aMediaStoreItems() {
        val shouldPreferFile = MediaItemBuilder.shouldPreferDirectLocalFileUri(
            contentUriString = "content://media/external/audio/media/42",
            filePath = "/storage/emulated/0/Music/test-track.m4a",
            mimeType = "audio/mp4"
        )

        assertThat(shouldPreferFile).isTrue()
    }

    @Test
    fun shouldPreferDirectLocalFileUri_keepsContentUriForFormatsThatAlreadySeekCorrectly() {
        val shouldPreferFile = MediaItemBuilder.shouldPreferDirectLocalFileUri(
            contentUriString = "content://media/external/audio/media/24",
            filePath = "/storage/emulated/0/Music/test-track.flac",
            mimeType = "audio/flac"
        )

        assertThat(shouldPreferFile).isFalse()
    }

    @Test
    fun shouldPreferDirectLocalFileUri_keepsCloudUrisUntouched() {
        val shouldPreferFile = MediaItemBuilder.shouldPreferDirectLocalFileUri(
            contentUriString = "telegram://123/456",
            filePath = "/storage/emulated/0/Download/cached-track.m4a",
            mimeType = "audio/mp4"
        )

        assertThat(shouldPreferFile).isFalse()
    }

    @Test
    fun playbackMimeType_clearsAmbiguousLocalM4aMimeType() {
        val playbackMimeType = MediaItemBuilder.playbackMimeType(
            contentUriString = "content://media/external/audio/media/42",
            filePath = "/storage/emulated/0/Music/test-track.m4a",
            mimeType = "audio/mp4"
        )

        assertThat(playbackMimeType).isNull()
    }

    @Test
    fun playbackMimeType_keepsNonMp4MimeTypeForLocalPlayback() {
        val playbackMimeType = MediaItemBuilder.playbackMimeType(
            contentUriString = "content://media/external/audio/media/84",
            filePath = "/storage/emulated/0/Music/test-track.flac",
            mimeType = "audio/flac"
        )

        assertThat(playbackMimeType).isEqualTo("audio/flac")
    }

    @Test
    fun sessionArtworkUriString_exposesLocalArtworkThroughTheSharedProvider() {
        val exposed = MediaItemBuilder.sessionArtworkUriString(
            packageName = "com.theveloper.pixelplay",
            rawArtworkUri = "pixelplay_local_art://song/42"
        )

        assertThat(exposed).isEqualTo("content://com.theveloper.pixelplay.artwork/song/42")
    }

    @Test
    fun sessionArtworkUriString_usesTheRunningPackageAuthority() {
        val exposed = MediaItemBuilder.sessionArtworkUriString(
            packageName = "com.theveloper.pixelplay.debug",
            rawArtworkUri = "pixelplay_local_art://song/42"
        )

        assertThat(exposed).isEqualTo("content://com.theveloper.pixelplay.debug.artwork/song/42")
    }

    @Test
    fun sessionArtworkUriString_keepsTheCacheBustToken() {
        val exposed = MediaItemBuilder.sessionArtworkUriString(
            packageName = "com.theveloper.pixelplay",
            rawArtworkUri = "pixelplay_local_art://song/42?t=1700000000"
        )

        assertThat(exposed)
            .isEqualTo("content://com.theveloper.pixelplay.artwork/song/42?t=1700000000")
    }

    @Test
    fun sessionArtworkUriString_leavesNonLocalArtworkToTheDefaultPath() {
        assertThat(
            MediaItemBuilder.sessionArtworkUriString(
                packageName = "com.theveloper.pixelplay",
                rawArtworkUri = "https://example.com/cover.jpg"
            )
        ).isNull()
        assertThat(
            MediaItemBuilder.sessionArtworkUriString(
                packageName = "com.theveloper.pixelplay",
                rawArtworkUri = null
            )
        ).isNull()
    }
}
