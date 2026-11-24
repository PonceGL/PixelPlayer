package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.os.PowerManager
import com.theveloper.pixelplay.MainCoroutineExtension
import com.theveloper.pixelplay.data.model.PlaybackQueueItemSnapshot
import com.theveloper.pixelplay.data.model.PlaybackQueueSnapshot
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.utils.MediaItemBuilder
import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import com.theveloper.pixelplay.data.sonos.session.SonosConnection
import com.theveloper.pixelplay.data.sonos.session.SonosSessionHolder
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import androidx.media3.session.MediaController

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class PlaybackStateHolderTest {

    private val dualPlayerEngine: DualPlayerEngine = mockk(relaxed = true)
    private val userPreferencesRepository: UserPreferencesRepository = mockk(relaxed = true)
    private val castStateHolder: CastStateHolder = mockk(relaxed = true)
    private val queueStateHolder: QueueStateHolder = mockk(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)
    private val sonosSession = SonosSessionHolder()
    private val speaker = SonosDevice(SonosUdn("RINCON_1"), "Salón", "192.168.1.50", 1)
    private val powerManager: PowerManager = mockk(relaxed = true)

    private fun createHolder() = PlaybackStateHolder(
        dualPlayerEngine = dualPlayerEngine,
        userPreferencesRepository = userPreferencesRepository,
        castStateHolder = castStateHolder,
        queueStateHolder = queueStateHolder,
        sonosSession = sonosSession,
        appContext = appContext
    )

    private fun snapshot(
        mediaId: String = "duplicate-song",
        positionMs: Long = 48_000L
    ) = PlaybackQueueSnapshot(
        items = listOf(
            PlaybackQueueItemSnapshot(
                mediaId = mediaId,
                uri = "file:///music/$mediaId.mp3"
            )
        ),
        currentMediaId = mediaId,
        currentIndex = 0,
        currentPositionMs = positionMs
    )

    init {
        every { appContext.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { powerManager.isInteractive } returns true
    }

    @Test
    fun `paused override does not bleed into later occurrence with same media id`() {
        val holder = createHolder()

        holder.ensureCurrentPlaybackOccurrence("duplicate-song")
        holder.rememberPausedPositionOverride("duplicate-song", 91_000L)

        holder.onPlaybackOccurrenceTransition("another-song")
        holder.onPlaybackOccurrenceTransition("duplicate-song")
        holder.syncCurrentPositionFromPlayer("duplicate-song", 0L)

        assertEquals(0L, holder.currentPosition.value)
    }

    @Test
    fun `clearing latest media controller restores previous activity controller`() {
        val holder = createHolder()
        val mainController = mockk<MediaController>(relaxed = true)
        val externalController = mockk<MediaController>(relaxed = true)

        holder.setMediaController(mainController)
        holder.setMediaController(externalController)

        assertSame(externalController, holder.mediaController)

        holder.clearMediaController(externalController)

        assertSame(mainController, holder.mediaController)
    }

    @Test
    fun `clearing stale media controller keeps active controller`() {
        val holder = createHolder()
        val mainController = mockk<MediaController>(relaxed = true)
        val externalController = mockk<MediaController>(relaxed = true)

        holder.setMediaController(externalController)
        holder.setMediaController(mainController)

        holder.clearMediaController(externalController)

        assertSame(mainController, holder.mediaController)
    }

    @Test
    fun `cold start snapshot only applies to the first matching occurrence`() = runTest {
        coEvery { userPreferencesRepository.getPlaybackQueueSnapshotOnce() } returns snapshot()

        val holder = createHolder()
        holder.initialize(this)
        advanceUntilIdle()

        holder.ensureCurrentPlaybackOccurrence("duplicate-song")
        holder.syncCurrentPositionFromPlayer("duplicate-song", 0L)
        assertEquals(48_000L, holder.currentPosition.value)

        holder.onPlaybackOccurrenceTransition("another-song")
        holder.onPlaybackOccurrenceTransition("duplicate-song")
        holder.syncCurrentPositionFromPlayer("duplicate-song", 0L)

        assertEquals(0L, holder.currentPosition.value)
    }

    @Test
    fun `late cold start snapshot binds to the already active first occurrence`() = runTest {
        coEvery { userPreferencesRepository.getPlaybackQueueSnapshotOnce() } returns snapshot()

        val holder = createHolder()
        holder.initialize(this)
        holder.ensureCurrentPlaybackOccurrence("duplicate-song")
        holder.syncCurrentPositionFromPlayer("duplicate-song", 0L)
        assertEquals(0L, holder.currentPosition.value)

        advanceUntilIdle()

        holder.syncCurrentPositionFromPlayer("duplicate-song", 0L)

        assertEquals(48_000L, holder.currentPosition.value)
    }

    @Test
    fun `late cold start snapshot is discarded after playback occurrence advances`() = runTest {
        coEvery { userPreferencesRepository.getPlaybackQueueSnapshotOnce() } returns snapshot()

        val holder = createHolder()
        holder.initialize(this)
        holder.ensureCurrentPlaybackOccurrence("duplicate-song")
        holder.onPlaybackOccurrenceTransition("another-song")
        holder.onPlaybackOccurrenceTransition("duplicate-song")

        advanceUntilIdle()

        holder.syncCurrentPositionFromPlayer("duplicate-song", 0L)

        assertEquals(0L, holder.currentPosition.value)
    }

    @Test
    fun `late cold start snapshot is discarded after first occurrence already ended`() = runTest {
        coEvery { userPreferencesRepository.getPlaybackQueueSnapshotOnce() } returns snapshot()

        val holder = createHolder()
        holder.initialize(this)
        holder.ensureCurrentPlaybackOccurrence("duplicate-song")
        holder.onPlaybackOccurrenceTransition(null)

        advanceUntilIdle()

        holder.ensureCurrentPlaybackOccurrence("duplicate-song")
        holder.syncCurrentPositionFromPlayer("duplicate-song", 0L)

        assertEquals(0L, holder.currentPosition.value)
    }

    private class SpeakerShuffle(val blocked: Int, val phonePlayerTouched: Boolean, val controller: MediaController, val shuffleOn: Boolean)

    private fun testSong(id: String) = Song(
        id = id, title = "Song $id", artist = "Artist", genre = "Rock", albumArtUriString = null, artistId = 1L, albumId = 1L,
        contentUriString = "content://dummy/$id", duration = 180_000L, bitrate = null, sampleRate = null, album = "Album",
        path = "path", mimeType = "audio/mpeg",
    )

    /** Turns the shuffle on with three songs and [connection] on, the middle one playing. */
    private fun shuffleWith(connection: SonosConnection, changeAvailable: Boolean = true): SpeakerShuffle {
        var blocked = 0
        var touchedLocalQueue = false
        io.mockk.every { dualPlayerEngine.masterPlayer } answers { touchedLocalQueue = true; mockk(relaxed = true) }
        val songs = listOf("a", "b", "c").map(::testSong)
        val controller: MediaController = mockk(relaxed = true)
        io.mockk.every { controller.currentMediaItem } returns MediaItem.Builder().setMediaId("b").build()
        io.mockk.every { controller.currentMediaItemIndex } returns 1
        io.mockk.every { controller.mediaItemCount } returns 3
        io.mockk.every { controller.getMediaItemAt(any()) } answers { MediaItem.Builder().setMediaId(listOf("a", "b", "c")[firstArg()]).build() }
        io.mockk.every { controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) } returns changeAvailable
        io.mockk.mockkObject(MediaItemBuilder)
        // The toggle waits a little after the last one: a clock that starts at zero would always be "too soon".
        io.mockk.mockkStatic(android.os.SystemClock::class)
        io.mockk.every { android.os.SystemClock.elapsedRealtime() } returns 1_000_000L
        io.mockk.every { MediaItemBuilder.build(any()) } answers { MediaItem.Builder().setMediaId(firstArg<Song>().id).build() }
        io.mockk.every { castStateHolder.castSession } returns kotlinx.coroutines.flow.MutableStateFlow<com.google.android.gms.cast.framework.CastSession?>(null)
        io.mockk.every { userPreferencesRepository.persistentShuffleEnabledFlow } returns kotlinx.coroutines.flow.flowOf(false)
        sonosSession.set(connection)
        val holder = createHolder()
        kotlinx.coroutines.test.runTest {
            holder.initialize(this, onSonosShuffleBlocked = { blocked++ })
            holder.setMediaController(controller)
            holder.toggleShuffle(songs, songs[1], "Album") { }
            advanceUntilIdle()
        }
        io.mockk.unmockkObject(MediaItemBuilder)
        io.mockk.unmockkStatic(android.os.SystemClock::class)
        return SpeakerShuffle(blocked, touchedLocalQueue, controller, holder.stablePlayerState.value.isShuffleEnabled)
    }

    @Test
    fun `shuffle is refused while a speaker is still being connected`() {
        val result = shuffleWith(SonosConnection.Connecting(speaker))

        assertEquals(1, result.blocked)
        assertEquals(false, result.shuffleOn)
    }

    @Test
    fun `shuffle is refused, saying so, while the speaker does not take changes to its queue yet`() {
        val result = shuffleWith(SonosConnection.Active(speaker), changeAvailable = false)

        assertEquals(1, result.blocked)
        assertEquals(false, result.shuffleOn)
        io.mockk.verify(exactly = 0) { result.controller.replaceMediaItems(any(), any(), any()) }
    }

    @Test
    fun `with a speaker only what comes after the playing song is replaced, in one go, through the session's player, never the phone's`() {
        val result = shuffleWith(SonosConnection.Active(speaker))

        assertEquals(0, result.blocked)
        assertEquals(true, result.shuffleOn)
        assertEquals(false, result.phonePlayerTouched)
        io.mockk.verify(exactly = 1) { result.controller.replaceMediaItems(2, 3, any()) }
        io.mockk.verify(exactly = 1) { result.controller.replaceMediaItems(any(), any(), any()) }
    }

    @Test
    fun `shuffle is not refused when no speaker is involved`() {
        val result = shuffleWith(SonosConnection.Idle)

        assertEquals(0, result.blocked)
        assertEquals(true, result.shuffleOn)
    }

    /** A queue on a player that remembers every replacement asked of it, to see what really reached the player. */
    private class FakeQueue(initial: List<String>, val playing: Int) {
        val ids = initial.toMutableList()
        val replacements = mutableListOf<Pair<Int, Int>>()
    }

    private fun controllerOver(queue: FakeQueue): MediaController {
        val controller: MediaController = mockk(relaxed = true)
        every { controller.currentMediaItemIndex } answers { queue.playing }
        every { controller.currentMediaItem } answers { MediaItem.Builder().setMediaId(queue.ids[queue.playing]).build() }
        every { controller.mediaItemCount } answers { queue.ids.size }
        every { controller.getMediaItemAt(any()) } answers { MediaItem.Builder().setMediaId(queue.ids[firstArg()]).build() }
        every { controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) } returns true
        every { controller.replaceMediaItems(any(), any(), any()) } answers {
            val from = firstArg<Int>()
            val to = secondArg<Int>()
            queue.replacements += from to to
            queue.ids.subList(from, to).clear()
            queue.ids.addAll(from, thirdArg<List<MediaItem>>().map { it.mediaId })
        }
        return controller
    }

    /** What the player and the screen have, and the replacements the player was asked for, after one toggle. */
    private class Toggles(val player: List<String>, val screen: List<String>, val shuffleOn: Boolean, val replacements: List<Pair<Int, Int>>)

    /** Lets the toggle run to its end: part of it works on a real thread, which the test clock does not wait for. */
    private fun kotlinx.coroutines.test.TestScope.settle(holder: PlaybackStateHolder) {
        advanceUntilIdle()
        repeat(1_000) {
            if (!holder.stablePlayerState.value.isShuffleTransitionInProgress) return
            Thread.sleep(5)
            advanceUntilIdle()
        }
    }

    /**
     * Turns the shuffle on, then, after [whileShuffled] has done something to the queue, off, with a speaker, and says
     * what the player and the screen have after each. The song with index 3 of "a".."h" plays.
     */
    private fun toggleOnThenOff(
        whileShuffled: (FakeQueue, MutableList<Song>) -> Unit = { _, _ -> },
    ): Pair<Toggles, Toggles> {
        val queue = FakeQueue(('a'..'h').map { it.toString() }, playing = 3)
        var screen: List<Song> = queue.ids.map(::testSong)
        var original: List<Song> = emptyList()
        var now = 1_000_000L
        io.mockk.every { queueStateHolder.setOriginalQueueOrder(any()) } answers { original = firstArg() }
        io.mockk.every { queueStateHolder.originalQueueOrder } answers { original }
        io.mockk.every { queueStateHolder.hasOriginalQueue() } answers { original.isNotEmpty() }
        io.mockk.mockkObject(MediaItemBuilder)
        io.mockk.mockkStatic(android.os.SystemClock::class)
        io.mockk.every { android.os.SystemClock.elapsedRealtime() } answers { now }
        io.mockk.every { MediaItemBuilder.build(any()) } answers { MediaItem.Builder().setMediaId(firstArg<Song>().id).build() }
        io.mockk.every { castStateHolder.castSession } returns kotlinx.coroutines.flow.MutableStateFlow<com.google.android.gms.cast.framework.CastSession?>(null)
        io.mockk.every { userPreferencesRepository.persistentShuffleEnabledFlow } returns kotlinx.coroutines.flow.flowOf(false)
        sonosSession.set(SonosConnection.Active(speaker))
        val holder = createHolder()
        lateinit var afterOn: Toggles
        lateinit var afterOff: Toggles
        kotlinx.coroutines.test.runTest {
            holder.initialize(this)
            holder.setMediaController(controllerOver(queue))
            holder.toggleShuffle(screen, screen[3], "Album") { screen = it }
            settle(holder)
            afterOn = Toggles(queue.ids.toList(), screen.map { it.id }, holder.stablePlayerState.value.isShuffleEnabled, queue.replacements.toList())
            queue.replacements.clear()

            val edited = screen.toMutableList()
            whileShuffled(queue, edited)
            screen = edited
            now += 10_000L
            holder.toggleShuffle(screen, screen[3], "Album") { screen = it }
            settle(holder)
            afterOff = Toggles(queue.ids.toList(), screen.map { it.id }, holder.stablePlayerState.value.isShuffleEnabled, queue.replacements.toList())
        }
        io.mockk.unmockkObject(MediaItemBuilder)
        io.mockk.unmockkStatic(android.os.SystemClock::class)
        return afterOn to afterOff
    }

    @Test
    fun `turning the shuffle on leaves the songs up to the playing one in place and mixes only the rest`() {
        val (on, _) = toggleOnThenOff()

        assertEquals(true, on.shuffleOn)
        assertEquals(listOf("a", "b", "c", "d"), on.screen.take(4))
        assertEquals(listOf("e", "f", "g", "h"), on.screen.drop(4).sorted())
        assertEquals(on.screen, on.player)
    }

    @Test
    fun `every change of the queue starts after the playing song, on and off`() {
        val (on, off) = toggleOnThenOff()

        assertEquals(listOf(4 to 8), on.replacements)
        assertEquals(listOf(4 to 8), off.replacements)
    }

    @Test
    fun `turning the shuffle off puts the rest back in order without touching the song that plays`() {
        val (_, off) = toggleOnThenOff()

        assertEquals(false, off.shuffleOn)
        assertEquals(('a'..'h').map { it.toString() }, off.player)
        assertEquals(off.player, off.screen)
    }

    @Test
    fun `a song added while shuffled stays in the queue after the shuffle is turned off, right after the playing one`() {
        val (_, off) = toggleOnThenOff { queue, screen ->
            queue.ids.add(4, "x")
            screen.add(4, testSong("x"))
        }

        assertEquals(listOf("a", "b", "c", "d", "x", "e", "f", "g", "h"), off.player)
        assertEquals(off.player, off.screen)
    }
}
