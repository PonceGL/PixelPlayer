package com.theveloper.pixelplay.data.sonos.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UpnpErrorsTest {

    @Test
    fun `711 on Next or Previous means there are no more tracks`() {
        assertThat(UpnpErrors.classify("Next", 711)).isEqualTo(UpnpErrorKind.NoMoreTracks)
        assertThat(UpnpErrors.classify("Previous", 711)).isEqualTo(UpnpErrorKind.NoMoreTracks)
    }

    @Test
    fun `711 on other actions is just an illegal seek target`() {
        assertThat(UpnpErrors.classify("Seek", 711)).isEqualTo(UpnpErrorKind.Other(711))
    }

    @Test
    fun `800 means the speaker is not the coordinator`() {
        assertThat(UpnpErrors.classify("Play", 800)).isEqualTo(UpnpErrorKind.NotCoordinator)
    }

    @Test
    fun `701 means the action is not allowed in the current state`() {
        assertThat(UpnpErrors.classify("Pause", 701)).isEqualTo(UpnpErrorKind.TransitionNotAvailable)
    }

    @Test
    fun `argument and media errors are recognised`() {
        assertThat(UpnpErrors.classify("SetVolume", 402)).isEqualTo(UpnpErrorKind.InvalidArguments)
        assertThat(UpnpErrors.classify("AddURIToQueue", 704)).isEqualTo(UpnpErrorKind.UnsupportedMedia)
        assertThat(UpnpErrors.classify("AddURIToQueue", 714)).isEqualTo(UpnpErrorKind.UnsupportedMedia)
        assertThat(UpnpErrors.classify("AddURIToQueue", 716)).isEqualTo(UpnpErrorKind.ResourceNotFound)
    }

    @Test
    fun `unknown or missing codes are kept as other`() {
        assertThat(UpnpErrors.classify("Play", 501)).isEqualTo(UpnpErrorKind.Other(501))
        assertThat(UpnpErrors.classify("Play", null)).isEqualTo(UpnpErrorKind.Other(null))
    }
}
