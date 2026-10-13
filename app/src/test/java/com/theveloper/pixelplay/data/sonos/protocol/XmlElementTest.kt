package com.theveloper.pixelplay.data.sonos.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class XmlElementTest {

    @Test
    fun `strips namespace prefixes from element and attribute names`() {
        val root = SafeXml.parse("""<s:Envelope><s:Body><u:X a:b="1"/></s:Body></s:Envelope>""")

        val x = root?.child("Body")?.child("X")

        assertThat(root?.name).isEqualTo("Envelope")
        assertThat(x?.attributes).containsExactly("b", "1")
    }

    @Test
    fun `returns text content of leaf elements`() {
        val root = SafeXml.parse("<a><b>hola &amp; adiós</b></a>")

        assertThat(root?.child("b")?.text).isEqualTo("hola & adiós")
    }

    @Test
    fun `finds nested descendants by local name`() {
        val root = SafeXml.parse("<a><b><c>1</c></b><c>2</c></a>")

        assertThat(root?.descendants("c")?.map { it.text }).containsExactly("1", "2").inOrder()
    }

    @Test
    fun `rejects documents with a DOCTYPE`() {
        val xml = """<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><x>&e;</x>"""

        assertThat(SafeXml.parse(xml)).isNull()
    }

    @Test
    fun `returns null for malformed or empty input`() {
        assertThat(SafeXml.parse("")).isNull()
        assertThat(SafeXml.parse("<a>")).isNull()
        assertThat(SafeXml.parse("no xml")).isNull()
    }

    @Test
    fun `captured speaker response parses`() {
        val body = com.theveloper.pixelplay.data.sonos.SonosFixtures
            .soapResponseBody("AVTransport.GetPositionInfo.ok")

        val track = SafeXml.parse(body)?.descendants("Track")?.firstOrNull()

        assertThat(track?.text).isEqualTo("1")
    }

    @Test
    fun `absurdly deep documents are rejected instead of overflowing the stack`() {
        val depth = 100_000
        val xml = "<a>".repeat(depth) + "</a>".repeat(depth)

        assertThat(SafeXml.parse(xml)).isNull()
    }

    @Test
    fun `documents nested as deep as real speaker payloads still parse`() {
        val depth = 40
        val xml = "<a>".repeat(depth) + "x" + "</a>".repeat(depth)

        assertThat(SafeXml.parse(xml)).isNotNull()
    }

    @Test
    fun `oversized documents are rejected before parsing`() {
        val xml = "<a>" + "x".repeat(SafeXml.MAX_DOCUMENT_CHARS) + "</a>"

        assertThat(SafeXml.parse(xml)).isNull()
    }
}
