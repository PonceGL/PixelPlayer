package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.model.PlayMode
import com.theveloper.pixelplay.data.sonos.model.SonosUdn
import com.theveloper.pixelplay.data.sonos.model.TrackMetadata
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.SonosTransport
import com.theveloper.pixelplay.data.sonos.protocol.Didl
import com.theveloper.pixelplay.data.sonos.protocol.SonosService
import com.theveloper.pixelplay.data.sonos.protocol.UpnpErrorKind
import com.theveloper.pixelplay.data.sonos.protocol.UpnpTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether a Next/Previous went anywhere. Running out of songs in a normal queue is an answer, not a failure. */
internal enum class Navigation { MOVED, NO_MORE }

/**
 * @property window the part of the given list that is now in the speaker's queue; its first song is track 1.
 * @property positionApplied false when the speaker refused to move to the requested second and starts the song from 0.
 */
internal data class LoadResult(val window: IntRange, val positionApplied: Boolean)

/**
 * Everything the app tells the coordinator of a speaker to do. Commands reach the speaker one at a time, in
 * the order they were given, and each one marks the instant it is sent so the changes it causes are not
 * mistaken for somebody else's.
 */
internal class SonosCommander(
    private val transport: SonosTransport,
    private val host: String,
    private val coordinatorUdn: SonosUdn,
    private val now: () -> Long,
    private val recordCommand: (atMs: Long) -> Unit,
) {

    private val order = Mutex()

    /**
     * Replaces the speaker's queue with [tracks] (at most [MAX_QUEUE] of them, around [startIndex]) and starts
     * the song at [startIndex] at [startPositionMs]. The songs up to the current one are queued first so playback
     * starts at once; [onStarted] is called when it has, and the rest is queued while it plays. Cancelling stops
     * the queueing and leaves whatever is already there.
     */
    suspend fun loadAndPlay(
        tracks: List<TrackMetadata>,
        startIndex: Int,
        startPositionMs: Long,
        mode: PlayMode,
        playWhenReady: Boolean,
        onStarted: (LoadResult) -> Unit,
        onProgress: (queued: Int) -> Unit,
    ): SonosResult<LoadResult> {
        if (startIndex !in tracks.indices) return SonosResult.Err(SonosFailure.MalformedResponse)
        val window = queueWindow(tracks.size, startIndex)
        val current = startIndex - window.first

        fail(send("RemoveAllTracksFromQueue"))?.let { return it }
        for (index in window.first..startIndex) fail(enqueue(tracks[index]))?.let { return it }
        fail(send("SetAVTransportURI", "CurrentURI" to "x-rincon-queue:${coordinatorUdn.value}#0", "CurrentURIMetaData" to ""))
            ?.let { return it }
        fail(send("Seek", "Unit" to "TRACK_NR", "Target" to (current + 1).toString()))?.let { return it }
        fail(send("SetPlayMode", "NewPlayMode" to mode.upnpValue))?.let { return it }
        // The speaker may refuse to move inside a song it has not started: starting from 0 beats not starting.
        val positionApplied = startPositionMs <= 0 || send("Seek", *timeTarget(startPositionMs)) is SonosResult.Ok
        if (playWhenReady) fail(send("Play", "Speed" to "1"))?.let { return it }

        val result = LoadResult(window, positionApplied)
        onStarted(result)
        var queued = current + 1
        for (index in startIndex + 1..window.last) {
            fail(enqueue(tracks[index]))?.let { return it }
            onProgress(++queued)
        }
        return SonosResult.Ok(result)
    }

    suspend fun play(): SonosResult<Unit> = send("Play", "Speed" to "1").unit()

    suspend fun pause(): SonosResult<Unit> = send("Pause").unit()

    suspend fun stop(): SonosResult<Unit> = send("Stop").unit()

    suspend fun next(): SonosResult<Navigation> = navigated(send("Next"))

    /** Like most players: after a few seconds "previous" restarts the song, before that it goes to the one before. */
    suspend fun previousSmart(positionMs: Long): SonosResult<Navigation> =
        if (positionMs > RESTART_THRESHOLD_MS) seekTo(0).map { Navigation.MOVED } else navigated(send("Previous"))

    suspend fun seekTo(positionMs: Long): SonosResult<Unit> = send("Seek", *timeTarget(positionMs)).unit()

    suspend fun seekToTrack(trackNumber: Int, positionMs: Long): SonosResult<Unit> {
        fail(send("Seek", "Unit" to "TRACK_NR", "Target" to trackNumber.coerceAtLeast(1).toString()))?.let { return it }
        return if (positionMs > 0) seekTo(positionMs) else SonosResult.Ok(Unit)
    }

    suspend fun setPlayMode(mode: PlayMode): SonosResult<Unit> = send("SetPlayMode", "NewPlayMode" to mode.upnpValue).unit()

    private suspend fun enqueue(track: TrackMetadata) = send(
        "AddURIToQueue",
        "EnqueuedURI" to track.resourceUrl,
        "EnqueuedURIMetaData" to Didl.build(track),
        "DesiredFirstTrackNumberEnqueued" to "0",
        "EnqueueAsNext" to "0",
    )

    private suspend fun send(action: String, vararg arguments: Pair<String, String>): SonosResult<Map<String, String>> =
        order.withLock {
            recordCommand(now())
            transport.call(host, SonosService.AV_TRANSPORT, action, listOf("InstanceID" to "0") + arguments)
        }

    private fun timeTarget(positionMs: Long) =
        arrayOf("Unit" to "REL_TIME", "Target" to (UpnpTime.format(positionMs.coerceAtLeast(0)) ?: "0:00:00"))

    private fun navigated(result: SonosResult<Map<String, String>>): SonosResult<Navigation> = when (result) {
        is SonosResult.Ok -> SonosResult.Ok(Navigation.MOVED)
        is SonosResult.Err -> {
            val failure = result.failure
            if (failure is SonosFailure.Upnp && failure.kind == UpnpErrorKind.NoMoreTracks) SonosResult.Ok(Navigation.NO_MORE) else result
        }
    }

    private fun fail(result: SonosResult<Map<String, String>>): SonosResult.Err? = result as? SonosResult.Err

    private fun SonosResult<Map<String, String>>.unit(): SonosResult<Unit> = map { }

    private inline fun <T, R> SonosResult<T>.map(transform: (T) -> R): SonosResult<R> = when (this) {
        is SonosResult.Ok -> SonosResult.Ok(transform(value))
        is SonosResult.Err -> this
    }

    companion object {
        /** Most songs queued on a speaker for now; longer queues are handled in a later part. */
        const val MAX_QUEUE = 1000
        private const val LEAD_SONGS = 100
        private const val RESTART_THRESHOLD_MS = 3_000L

        /** The [MAX_QUEUE] songs of a list of [size] that surround [startIndex], keeping the list's order. */
        fun queueWindow(size: Int, startIndex: Int): IntRange {
            if (size <= MAX_QUEUE) return 0 until size
            val first = (startIndex - LEAD_SONGS).coerceIn(0, size - MAX_QUEUE)
            return first until first + MAX_QUEUE
        }
    }
}
