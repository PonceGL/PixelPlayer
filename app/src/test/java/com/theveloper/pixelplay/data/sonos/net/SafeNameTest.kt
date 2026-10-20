package com.theveloper.pixelplay.data.sonos.net

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind
import org.junit.jupiter.api.Test

class SafeNameTest {

    @Test
    fun `a refusal says which one it was, with the code when the speaker gave one we do not know`() {
        assertThat(SonosFailure.Upnp("Seek", UpnpErrorKind.InvalidArguments).safeName()).isEqualTo("Upnp(InvalidArguments)")
        assertThat(SonosFailure.Upnp("Seek", UpnpErrorKind.Other(501)).safeName()).isEqualTo("Upnp(Other, 501)")
    }

    @Test
    fun `other failures are named by kind alone`() {
        assertThat(SonosFailure.Timeout.safeName()).isEqualTo("Timeout")
    }
}
