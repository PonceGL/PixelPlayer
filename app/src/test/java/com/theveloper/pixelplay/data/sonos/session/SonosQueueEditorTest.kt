package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import com.theveloper.pixelplay.data.sonos.model.TrackMetadata
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.SonosTransport
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SonosQueueEditorTest {

    /** A speaker that keeps a queue of addresses and answers the commands the way a Move does. */
    private class FakeSpeaker : SonosTransport {
        var queue = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val actions = mutableListOf<String>()
        var script: (String, Int) -> SonosFailure? = { _, _ -> null }
        var gate: suspend (Int) -> Unit = {}

        override suspend fun call(
            host: String,
            service: SonosService,
            action: String,
            arguments: List<Pair<String, String>>,
            timeoutMs: Long,
        ): SonosResult<Map<String, String>> {
            actions += action
            gate(actions.size)
            script(action, actions.size)?.let { return SonosResult.Err(it) }
            fun arg(name: String) = arguments.single { it.first == name }.second
            when (action) {
                "RemoveAllTracksFromQueue" -> queue.clear()
                "AddMultipleURIsToQueue" -> {
                    val urls = arg("EnqueuedURIs").split(" ")
                    val first = arg("DesiredFirstTrackNumberEnqueued").toInt()
                    queue.addAll(if (first == 0) queue.size else first - 1, urls)
                }
                "AddURIToQueue" -> queue.add(arg("EnqueuedURI"))
                "RemoveTrackRangeFromQueue" -> {
                    val start = arg("StartingIndex").toInt() - 1
                    repeat(arg("NumberOfTracks").toInt()) { removed += queue.removeAt(start) }
                }
                "ReorderTracksInQueue" -> {
                    val start = arg("StartingIndex").toInt() - 1
                    val count = arg("NumberOfTracks").toInt()
                    val before = arg("InsertBefore").toInt() - 1
                    val block = queue.subList(start, start + count).toList()
                    if (before <= start || before >= start + count) {
                        val after = before >= start + count
                        repeat(count) { queue.removeAt(start) }
                        queue.addAll(if (after) before - count else before, block)
                    }
                }
            }
            return SonosResult.Ok(emptyMap())
        }
    }

    private fun track(id: String) = TrackMetadata(id, null, null, null, 1000, "audio/flac", "http://phone/song/$id")

    private class Fixture(val scope: TestScope, initial: List<String>, speaker: FakeSpeaker) {
        val published = mutableListOf<List<String>>()
        val finished = mutableListOf<SonosResult<Unit>>()
        var playing: Int? = 0
        val reloads = mutableListOf<List<String>>()
        var unservable = setOf<String>()
        var trackOfOverride: ((String) -> TrackMetadata?)? = null
        val trackCalls = mutableMapOf<String, Int>()
        val commander = SonosCommander(speaker, "h", SonosUdn("RINCON_1"), now = { scope.testScheduler.currentTime }, recordCommand = {})
        val editor = SonosQueueEditor(
            commander = commander,
            scope = scope.backgroundScope,
            initial = initial,
            idOf = { it },
            trackOf = { id ->
                trackCalls.merge(id, 1, Int::plus)
                trackOfOverride?.invoke(id) ?: if (id in unservable) null else TrackMetadata(id, null, null, null, 1000, "audio/flac", "http://phone/song/$id")
            },
            playingIndex = { playing },
            publish = { published += it },
            reload = { _, tracks -> reloads += tracks.map { it.title }; SonosResult.Ok(Unit) },
            onFinished = { finished += it },
        )
    }

    private fun test(initial: String, block: suspend TestScope.(Fixture, FakeSpeaker) -> Unit) = runTest {
        val speaker = FakeSpeaker().also { s -> s.queue = initial.map { "http://phone/song/$it" }.toMutableList() }
        block(Fixture(this, initial.map { it.toString() }, speaker), speaker)
    }

    private fun FakeSpeaker.ids() = queue.map { it.substringAfterLast('/') }
    private fun ids(text: String) = text.map { it.toString() }

    @Test
    fun `songs added at the end go in one batch and the speaker ends with the new queue`() = test("abc") { f, speaker ->
        f.editor.submit(ids("abcde"))
        runCurrent()

        assertThat(speaker.ids()).containsExactly("a", "b", "c", "d", "e").inOrder()
        assertThat(speaker.actions).containsExactly("AddMultipleURIsToQueue")
        assertThat(f.editor.speaker).isEqualTo(ids("abcde"))
        assertThat(f.finished).containsExactly(SonosResult.Ok(Unit))
    }

    @Test
    fun `what is about to be played is published before any command goes out`() = test("abc") { f, speaker ->
        speaker.gate = { n -> if (n == 1) assertThat(f.published).isNotEmpty() }
        f.editor.submit(ids("abcd"))
        runCurrent()

        assertThat(f.published.first()).containsExactly("a", "b", "c", "d")
    }

    @Test
    fun `a shuffle is rebuilt around the playing song, which is never taken out`() = test("abcdef") { f, speaker ->
        f.playing = 2
        f.editor.submit(ids("fdcbea"))
        runCurrent()

        assertThat(speaker.ids()).containsExactly("f", "d", "c", "b", "e", "a").inOrder()
        assertThat(speaker.removed).doesNotContain("http://phone/song/c")
        assertThat(f.editor.speaker).isEqualTo(ids("fdcbea"))
    }

    @Test
    fun `removing the playing song and others is a removal`() = test("abcd") { f, speaker ->
        f.playing = 1
        f.editor.submit(ids("ad"))
        runCurrent()

        assertThat(speaker.ids()).containsExactly("a", "d").inOrder()
    }

    @Test
    fun `a block moved is a move`() = test("abcde") { f, speaker ->
        f.playing = 2
        f.editor.submit(ids("adbce"))
        runCurrent()

        assertThat(speaker.ids()).containsExactly("a", "d", "b", "c", "e").inOrder()
        assertThat(speaker.actions).containsExactly("ReorderTracksInQueue")
    }

    @Test
    fun `a newer queue overtakes the one in progress, and the rest of the old plan is not sent`() = test("abcdef") { f, speaker ->
        f.playing = 0
        val release = CompletableDeferred<Unit>()
        speaker.gate = { n -> if (n == 1) release.await() }

        f.editor.submit(ids("afedcb"))
        runCurrent()
        f.editor.submit(ids("abcdef"))
        runCurrent()
        release.complete(Unit)
        runCurrent()

        assertThat(speaker.ids()).containsExactly("a", "b", "c", "d", "e", "f").inOrder()
        assertThat(f.editor.speaker).isEqualTo(ids("abcdef"))
        // The first plan took out what was after "a" and was stopped there: the second one only had to put it back.
        assertThat(speaker.actions).containsExactly("RemoveTrackRangeFromQueue", "AddMultipleURIsToQueue").inOrder()
    }

    @Test
    fun `a failure in the middle leaves what the speaker really has, and says so`() = test("abcdef") { f, speaker ->
        f.playing = 2
        speaker.script = { action, n -> if (n == 3) SonosFailure.Timeout else null }
        f.editor.submit(ids("fdcbea"))
        runCurrent()

        assertThat(f.finished.single()).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
        assertThat(f.editor.speaker).isEqualTo(speaker.ids())
    }

    @Test
    fun `songs the speaker cannot get are left out of what it is sent`() = test("abc") { f, speaker ->
        f.unservable = setOf("x")
        f.editor.submit(ids("abxc"))
        runCurrent()

        assertThat(speaker.ids()).containsExactly("a", "b", "c").inOrder()
        assertThat(f.editor.speaker).isEqualTo(ids("abc"))
    }

    @Test
    fun `when nothing can be kept the queue is loaded again with the servable songs`() = test("abcd") { f, speaker ->
        f.playing = null
        f.editor.submit(ids("dcba"))
        runCurrent()

        assertThat(f.reloads.single()).containsExactly("d", "c", "b", "a").inOrder()
        assertThat(f.editor.speaker).isEqualTo(ids("dcba"))
    }

    @Test
    fun `a queue that is already on the speaker sends nothing`() = test("abc") { f, speaker ->
        f.editor.submit(ids("abc"))
        runCurrent()

        assertThat(speaker.actions).isEmpty()
        assertThat(f.finished).containsExactly(SonosResult.Ok(Unit))
    }

    @Test
    fun `the editor goes on after a request that blew up`() = test("abc") { f, speaker ->
        f.trackOfOverride = { id -> if (id == "x") error("no address for this one") else null }
        f.editor.submit(ids("abx"))
        runCurrent()

        assertThat(f.finished.single()).isInstanceOf(SonosResult.Err::class.java)

        f.trackOfOverride = null
        f.editor.submit(ids("abcd"))
        runCurrent()

        assertThat(speaker.ids()).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(f.finished.last()).isEqualTo(SonosResult.Ok(Unit))
    }

    @Test
    fun `what the speaker needs to know about a song is asked once per request, and a changed answer does not break the plan`() = test("abc") { f, speaker ->
        var asked = 0
        f.trackOfOverride = { id ->
            if (id == "d" && ++asked > 1) null else TrackMetadata(id, null, null, null, 1000, "audio/flac", "http://phone/song/$id")
        }
        f.editor.submit(ids("abcd"))
        runCurrent()

        assertThat(speaker.ids()).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(f.trackCalls["d"]).isEqualTo(1)
        assertThat(f.finished.single()).isEqualTo(SonosResult.Ok(Unit))
    }
}
