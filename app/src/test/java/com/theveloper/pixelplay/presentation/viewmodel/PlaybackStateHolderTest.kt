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
    fun `with a speaker the queue is changed in two replacements through the session's player, never the phone's`() {
        val result = shuffleWith(SonosConnection.Active(speaker))

        assertEquals(0, result.blocked)
        assertEquals(true, result.shuffleOn)
        assertEquals(false, result.phonePlayerTouched)
        io.mockk.verify(exactly = 2) { result.controller.replaceMediaItems(any(), any(), any()) }
    }

    @Test
    fun `shuffle is not refused when no speaker is involved`() {
        val result = shuffleWith(SonosConnection.Idle)

        assertEquals(0, result.blocked)
        assertEquals(true, result.shuffleOn)
    }
}
