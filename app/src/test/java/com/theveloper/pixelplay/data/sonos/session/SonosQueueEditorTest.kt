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
        var readOverride: (suspend () -> SonosResult<List<String?>>)? = null
        var reportedOverride: Int? = null
        var reloadResult: SonosResult<Unit> = SonosResult.Ok(Unit)
        val reads = mutableListOf<List<String>>()
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
            readSpeaker = {
                readOverride?.invoke() ?: SonosResult.Ok(speaker.queue.map { it.substringAfterLast('/') }).also { reads += speaker.queue.map { u -> u.substringAfterLast('/') } }
            },
            reportedSize = { reportedOverride ?: speaker.queue.size },
            reload = { _, tracks -> reloads += tracks.map { it.title }; reloadResult },
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

    @Test
    fun `when the speaker reports another length than the editor believes, it reads the real queue first`() = test("abcdef") { f, speaker ->
        // "d" vanished from the speaker without the editor knowing.
        speaker.queue.remove("http://phone/song/d")
        f.playing = 2
        f.editor.submit(ids("fecbaz".replace('z', 'a').take(5)))
        runCurrent()

        assertThat(f.reads).isNotEmpty()
        assertThat(speaker.ids()).isEqualTo(ids("fecba".take(5)))
        assertThat(f.editor.speaker).isEqualTo(speaker.ids())
    }

    @Test
    fun `a refusal from the speaker makes it read the real queue and try once more`() = test("abcdef") { f, speaker ->
        f.playing = 2
        // The real queue lost "f"; the first removal (believing six songs) is refused, the retry after reading works.
        speaker.queue.remove("http://phone/song/f")
        f.reportedOverride = 6
        var refused = false
        speaker.script = { action, _ ->
            if (action == "RemoveTrackRangeFromQueue" && !refused) { refused = true; SonosFailure.Upnp(action, com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind.Other(402)) } else null
        }
        f.readOverride = { f.reportedOverride = null; SonosResult.Ok(speaker.ids()) }
        f.editor.submit(ids("edcba"))
        runCurrent()

        assertThat(speaker.ids()).isEqualTo(ids("edcba"))
        assertThat(f.finished.single()).isEqualTo(SonosResult.Ok(Unit))
    }

    @Test
    fun `a second refusal after reading again is told to the user, with no more tries`() = test("abcd") { f, speaker ->
        f.playing = 1
        speaker.script = { action, _ ->
            if (action == "RemoveTrackRangeFromQueue") SonosFailure.Upnp(action, com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind.Other(402)) else null
        }
        f.editor.submit(ids("ab"))
        runCurrent()

        assertThat(f.finished.single()).isInstanceOf(SonosResult.Err::class.java)
        assertThat(speaker.actions.count { it == "RemoveTrackRangeFromQueue" }).isEqualTo(2)
    }

    @Test
    fun `a speaker that cannot be read is told to the user`() = test("abc") { f, speaker ->
        f.reportedOverride = 9
        f.readOverride = { SonosResult.Err(SonosFailure.Timeout) }
        f.editor.submit(ids("acb"))
        runCurrent()

        assertThat(f.finished.single()).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
        assertThat(speaker.actions).isEmpty()
    }

    @Test
    fun `entries on the speaker that are not ours cannot be kept, the queue is loaded again`() = test("abc") { f, speaker ->
        f.reportedOverride = 4
        f.readOverride = { SonosResult.Ok(listOf("a", null, "b", "c")) }
        f.editor.submit(ids("cba"))
        runCurrent()

        assertThat(f.reloads.single()).containsExactly("c", "b", "a").inOrder()
    }

    @Test
    fun `a reload that fails after reading entries that are not ours is told to the user`() = test("abc") { f, speaker ->
        f.reportedOverride = 4
        f.readOverride = { SonosResult.Ok(listOf("a", null, "b", "c")) }
        f.reloadResult = SonosResult.Err(SonosFailure.Timeout)
        f.editor.submit(ids("cba"))
        runCurrent()

        assertThat(f.finished.single()).isEqualTo(SonosResult.Err(SonosFailure.Timeout))
    }

    @Test
    fun `a closed editor sends nothing`() = test("abc") { f, speaker ->
        f.editor.close()
        f.editor.submit(ids("abcde"))
        runCurrent()

        assertThat(speaker.actions).isEmpty()
        assertThat(f.finished).isEmpty()
    }

    @Test
    fun `closing the editor in the middle of a plan stops the rest of it`() = test("abcdef") { f, speaker ->
        f.playing = 1 // "b" stays, so the plan is edits and not a new load
        val release = CompletableDeferred<Unit>()
        speaker.gate = { n -> if (n == 1) release.await() }
        f.editor.submit(ids("xbcdey")) // takes out "a" and "f" and puts "x" and "y": more than one command
        runCurrent()

        f.editor.close()
        release.complete(Unit)
        runCurrent()

        assertThat(speaker.actions).hasSize(1)
        assertThat(f.finished).isEmpty()
    }
}
