package com.theveloper.pixelplay.data.sonos.server

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test

class SonosMediaAccessTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val access = SonosMediaAccess { SonosServerIdentity(token, 8080) }

    @Test
    fun `the right token for a published song is allowed`() {
        access.publish(listOf("42"))

        assertThat(access.authorize(token, "42")).isEqualTo(AccessDecision.Allowed)
    }

    @Test
    fun `a missing or wrong token is rejected`() {
        access.publish(listOf("42"))
        val wrong = listOf(
            null,
            "",
            token.dropLast(1),
            token + "0",
            token.dropLast(1) + "e",
            token.uppercase(),
            " $token",
        )

        for (candidate in wrong) {
            assertThat(access.authorize(candidate, "42")).isEqualTo(AccessDecision.InvalidToken)
        }
    }

    @Test
    fun `the right token for a song that was not published is not found`() {
        access.publish(listOf("42"))

        assertThat(access.authorize(token, "43")).isEqualTo(AccessDecision.NotPublished)
    }

    @Test
    fun `a wrong token is reported before anything about the song`() {
        assertThat(access.authorize("bad", "never-published")).isEqualTo(AccessDecision.InvalidToken)
    }

    @Test
    fun `hostile song ids are never published`() {
        access.publish(listOf("42"))
        val hostile = listOf(
            "../42",
            "../../etc/passwd",
            "%2e%2e%2f42",
            "42/../43",
            "",
            "   ",
            "4".repeat(10_000),
            "42\u0000",
            "\u0000",
            "42 ",
            " 42",
        )

        for (id in hostile) {
            assertThat(access.authorize(token, id)).isEqualTo(AccessDecision.NotPublished)
        }
        assertThat(access.authorize(token, null)).isEqualTo(AccessDecision.NotPublished)
    }

    @Test
    fun `publishing accumulates because the speaker queue keeps older urls`() {
        access.publish(listOf("1", "2"))
        access.publish(listOf("3"))

        for (id in listOf("1", "2", "3")) {
            assertThat(access.authorize(token, id)).isEqualTo(AccessDecision.Allowed)
        }
    }

    @Test
    fun `blank ids are ignored when publishing`() {
        access.publish(listOf("", "  ", "7"))

        assertThat(access.authorize(token, "")).isEqualTo(AccessDecision.NotPublished)
        assertThat(access.authorize(token, "7")).isEqualTo(AccessDecision.Allowed)
    }

    @Test
    fun `clearing forgets everything published`() {
        access.publish(listOf("1"))

        access.clear()

        assertThat(access.authorize(token, "1")).isNotEqualTo(AccessDecision.Allowed)
        assertThat(access.authorize(token, "1")).isEqualTo(AccessDecision.InvalidToken)
    }

    @Test
    fun `nothing is asked of the identity while nothing is published`() {
        var asked = false
        val idle = SonosMediaAccess { asked = true; SonosServerIdentity(token, null) }

        val decision = idle.authorize(token, "42")

        assertThat(decision).isEqualTo(AccessDecision.InvalidToken)
        assertThat(asked).isFalse()
    }

    @Test
    fun `the token is read from the identity each time`() {
        var current = token
        val rotating = SonosMediaAccess { SonosServerIdentity(current, null) }
        rotating.publish(listOf("1"))
        current = "f".repeat(32)

        assertThat(rotating.authorize(token, "1")).isEqualTo(AccessDecision.InvalidToken)
        assertThat(rotating.authorize("f".repeat(32), "1")).isEqualTo(AccessDecision.Allowed)
    }

    @Test
    fun `song and art urls carry the token revision and a decodable id`() {
        val song = access.songUrl("http://192.168.1.20:8080", "42", "r7").toHttpUrl()
        val art = access.artUrl("http://192.168.1.20:8080", "42", "r7").toHttpUrl()

        assertThat(song.pathSegments).containsExactly("sonos", "song", "42").inOrder()
        assertThat(art.pathSegments).containsExactly("sonos", "art", "42").inOrder()
        assertThat(song.queryParameter("auth")).isEqualTo(token)
        assertThat(song.queryParameter("v")).isEqualTo("r7")
        assertThat(song.host).isEqualTo("192.168.1.20")
        assertThat(song.port).isEqualTo(8080)
    }

    @Test
    fun `ids with spaces accents and reserved characters survive the round trip`() {
        val ids = listOf("cancion nueva", "Café (live)", "a&b=c", "x#y", "q?z", "100%", "ñandú/ü")

        for (id in ids) {
            val url = access.songUrl("http://10.0.0.5:9000", id, "1").toHttpUrl()

            assertThat(url.pathSegments).hasSize(3)
            assertThat(url.pathSegments[2]).isEqualTo(id)
            assertThat(url.queryParameter("auth")).isEqualTo(token)
        }
    }

    @Test
    fun `an invalid base address is a programming error`() {
        val failure = runCatching { access.songUrl("not a url", "1", "1") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `decisions never contain the token`() {
        assertThat(AccessDecision.Allowed.toString()).doesNotContain(token)
        assertThat(AccessDecision.InvalidToken.toString()).doesNotContain(token)
        assertThat(AccessDecision.NotPublished.toString()).doesNotContain(token)
    }

    @Test
    fun `redaction hides the token wherever it appears in a url`() {
        val url = access.songUrl("http://192.168.1.20:8080", "42", "r7")

        val redacted = redactToken("GET $url failed; retry $url")

        assertThat(redacted).doesNotContain(token)
        assertThat(redacted).contains("auth=<redacted>")
        assertThat(redacted).contains("/sonos/song/42")
    }

    @Test
    fun `redaction handles a token first in the query and at the end of the text`() {
        assertThat(redactToken("http://h/p?auth=$token&v=1")).isEqualTo("http://h/p?auth=<redacted>&v=1")
        assertThat(redactToken("http://h/p?v=1&auth=$token")).isEqualTo("http://h/p?v=1&auth=<redacted>")
    }

    @Test
    fun `redaction leaves text without a token untouched`() {
        assertThat(redactToken("GET /sonos/song/42 200")).isEqualTo("GET /sonos/song/42 200")
        assertThat(redactToken("author=me")).isEqualTo("author=me")
    }
}
