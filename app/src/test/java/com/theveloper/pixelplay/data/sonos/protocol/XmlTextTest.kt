package com.theveloper.pixelplay.data.sonos.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class XmlTextTest {

    @Test
    fun `escapes the five XML special characters`() {
        assertThat(XmlText.escape("""a<b>&"c"'d'""")).isEqualTo("a&lt;b&gt;&amp;&quot;c&quot;&apos;d&apos;")
    }

    @Test
    fun `keeps accents and emojis`() {
        assertThat(XmlText.escape("Canción 🎵 ñ")).isEqualTo("Canción 🎵 ñ")
    }

    @Test
    fun `removes control characters that XML 1_0 forbids but keeps tab and line breaks`() {
        assertThat(XmlText.escape("a\u0001b\u001Fc\td\ne\r")).isEqualTo("abc\td\ne\r")
    }

    @Test
    fun `removes lone surrogates`() {
        assertThat(XmlText.escape("x\uD800y")).isEqualTo("xy")
    }
}
