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
import java.util.concurrent.atomic.AtomicLong
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
    private val recordQueueEdit: (atMs: Long) -> Unit = recordCommand,
) {

    private val order = Mutex()
    private val loads = AtomicLong()

    /** How many songs go in one command; it goes down when the speaker refuses a batch and stays down. */
    @Volatile private var batchSize = MAX_BATCH

    /**
     * Replaces the speaker's queue with [tracks] and starts the song at [startIndex] at [startPositionMs]. Only
     * the part of the list around that song goes to the speaker (see [queueWindow]). The songs up to the current
     * one are queued first so playback starts quickly; [onStarted] is called when it has, and the rest is queued
     * while it plays, reporting the length of the queue after each song through [onProgress]. The caller must not
     * give other commands before [onStarted]. A failure after [onStarted] leaves a shorter queue on the speaker;
     * the last progress tells how long. Cancelling stops the queueing and leaves whatever is already there, and
     * a newer load on this commander ends an older one with [SonosFailure.Superseded].
     *
     * The play mode should be one that keeps the order the app sent (`NORMAL` or `REPEAT_*`): the speaker
     * reorders its queue under `SHUFFLE*` and the song numbers no longer mean the same.
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
        require(startIndex in tracks.indices) { "startIndex $startIndex is outside a queue of ${tracks.size}" }
        val load = loads.incrementAndGet()
        val window = queueWindow(tracks.size, startIndex)
        val current = startIndex - window.first

        fail(send("RemoveAllTracksFromQueue", load = load))?.let { return it }
        for (index in window.first..startIndex) fail(enqueue(tracks[index], load, markWindow = true))?.let { return it }
        fail(send("SetAVTransportURI", "CurrentURI" to "x-rincon-queue:${coordinatorUdn.value}#0", "CurrentURIMetaData" to "", load = load))
            ?.let { return it }
        // Before the song is selected: changing the mode afterwards can move the speaker to another song.
        fail(send("SetPlayMode", "NewPlayMode" to mode.upnpValue, load = load))?.let { return it }
        fail(send("Seek", "Unit" to "TRACK_NR", "Target" to (current + 1).toString(), load = load))?.let { return it }
        var positionApplied = true
        if (startPositionMs > 0) {
            val seek = send("Seek", *timeTarget(startPositionMs), load = load)
            // The speaker may refuse to move inside a song it has not started: starting from 0 beats not starting.
            // Any other failure (network, a newer load) is not a refusal and ends the load.
            if (seek is SonosResult.Err) {
                if (seek.failure !is SonosFailure.Upnp) return seek
                positionApplied = false
            }
        }
        if (playWhenReady) fail(send("Play", "Speed" to "1", load = load))?.let { return it }

        val result = LoadResult(window, positionApplied)
        onStarted(result)
        var queued = current + 1
        for (index in startIndex + 1..window.last) {
            // The rest does not change what is playing: the changes it causes must not hide what somebody else does.
            fail(enqueue(tracks[index], load, markWindow = false))?.let { return it }
            onProgress(++queued)
        }
        return SonosResult.Ok(result)
    }

    /**
     * Puts [tracks] in the speaker's queue starting at track number [position] (1 is the front, 0 the end), in batches.
     * The song that plays is not touched. Songs that went in stay if a batch fails; [onProgress] says how many have
     * gone in after each batch. A load that starts meanwhile ends this with [SonosFailure.Superseded].
     */
    suspend fun insertTracks(tracks: List<TrackMetadata>, position: Int, onProgress: (inserted: Int) -> Unit = {}): SonosResult<Unit> {
        if (tracks.isEmpty()) return SonosResult.Ok(Unit)
        val load = loads.get()
        var inserted = 0
        while (inserted < tracks.size) {
            val count = minOf(batchSize, tracks.size - inserted)
            val batch = tracks.subList(inserted, inserted + count)
            val result = send(
                "AddMultipleURIsToQueue",
                "UpdateID" to "0",
                "NumberOfURIs" to count.toString(),
                "EnqueuedURIs" to batch.joinToString(" ") { it.resourceUrl },
                "EnqueuedURIsMetaData" to batch.joinToString(" ") { Didl.build(it) },
                "ContainerURI" to "",
                "ContainerMetaData" to "",
                "DesiredFirstTrackNumberEnqueued" to (if (position == 0) 0 else position + inserted).toString(),
                "EnqueueAsNext" to "0",
                load = load,
                edit = true,
            )
            if (result is SonosResult.Err) {
                val refused = (result.failure as? SonosFailure.Upnp)?.kind == UpnpErrorKind.InvalidArguments
                if (!refused || count == 1) return result
                batchSize = maxOf(1, count / 2)
                continue
            }
            inserted += count
            onProgress(inserted)
        }
        return SonosResult.Ok(Unit)
    }

    /** Takes [count] songs out of the queue from track number [first]. If the one that plays is among them, the speaker goes on to the next. */
    suspend fun removeTracks(first: Int, count: Int): SonosResult<Unit> {
        if (count <= 0) return SonosResult.Ok(Unit)
        return send(
            "RemoveTrackRangeFromQueue",
            "UpdateID" to "0",
            "StartingIndex" to first.toString(),
            "NumberOfTracks" to count.toString(),
            edit = true,
        ).unit()
    }

    /** Moves [count] songs from track number [first] to just before the track that is number [before] now (the length plus one is the end). */
    suspend fun moveTracks(first: Int, count: Int, before: Int): SonosResult<Unit> {
        if (count <= 0) return SonosResult.Ok(Unit)
        return send(
            "ReorderTracksInQueue",
            "StartingIndex" to first.toString(),
            "NumberOfTracks" to count.toString(),
            "InsertBefore" to before.toString(),
            "UpdateID" to "0",
            edit = true,
        ).unit()
    }

    suspend fun play(): SonosResult<Unit> = send("Play", "Speed" to "1").unit()

    suspend fun pause(): SonosResult<Unit> = send("Pause").unit()

    suspend fun stop(): SonosResult<Unit> = send("Stop").unit()

    suspend fun next(): SonosResult<Navigation> = navigated(send("Next"))

    /** Like most players: after a few seconds "previous" restarts the song, before that it goes to the one before. */
    suspend fun previousSmart(positionMs: Long): SonosResult<Navigation> =
        if (positionMs > RESTART_THRESHOLD_MS) seekTo(0).map { Navigation.MOVED } else navigated(send("Previous"))

    suspend fun seekTo(positionMs: Long): SonosResult<Unit> = send("Seek", *timeTarget(positionMs)).unit()

    /** Ok(false) when the song changed but the speaker refused to move to [positionMs], so it plays from 0. */
    suspend fun seekToTrack(trackNumber: Int, positionMs: Long): SonosResult<Boolean> {
        fail(send("Seek", "Unit" to "TRACK_NR", "Target" to trackNumber.coerceAtLeast(1).toString()))?.let { return it }
        if (positionMs <= 0) return SonosResult.Ok(true)
        return when (val seek = seekTo(positionMs)) {
            is SonosResult.Ok -> SonosResult.Ok(true)
            is SonosResult.Err -> if (seek.failure is SonosFailure.Upnp) SonosResult.Ok(false) else seek
        }
    }

    suspend fun setPlayMode(mode: PlayMode): SonosResult<Unit> = send("SetPlayMode", "NewPlayMode" to mode.upnpValue).unit()

    private suspend fun enqueue(track: TrackMetadata, load: Long, markWindow: Boolean) = send(
        "AddURIToQueue",
        "EnqueuedURI" to track.resourceUrl,
        "EnqueuedURIMetaData" to Didl.build(track),
        "DesiredFirstTrackNumberEnqueued" to "0",
        "EnqueueAsNext" to "0",
        load = load,
        markWindow = markWindow,
    )

    /**
     * One command, alone on the line to the speaker. [load] ties a step to the load that sent it, so a step of an
     * older load is dropped once a newer one started. The window is marked before the command goes out and again
     * when it is done, so a slow command does not leave the changes it causes outside of it.
     */
    private suspend fun send(
        action: String,
        vararg arguments: Pair<String, String>,
        load: Long? = null,
        markWindow: Boolean = true,
        edit: Boolean = false,
    ): SonosResult<Map<String, String>> = order.withLock {
        if (load != null && load != loads.get()) return@withLock SonosResult.Err(SonosFailure.Superseded)
        val mark = if (edit) recordQueueEdit else recordCommand
        if (markWindow) mark(now())
        val result = transport.call(host, SonosService.AV_TRANSPORT, action, listOf("InstanceID" to "0") + arguments)
        if (markWindow) mark(now())
        result
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

        /** Most songs the speaker takes in one `AddMultipleURIsToQueue` (measured on a Move: more gives error 402). */
        const val MAX_BATCH = 16
        private const val LEAD_SONGS = 100
        private const val RESTART_THRESHOLD_MS = 3_000L

        /**
         * The part of a list of [size] songs that goes to the speaker: at most [MAX_QUEUE], in the list's order, with
         * at most [LEAD_SONGS] before [startIndex] so playback does not wait for the whole front of a long list to be
         * queued. Songs before the window are not on the speaker until a later part completes the queue.
         */
        fun queueWindow(size: Int, startIndex: Int): IntRange {
            val first = (startIndex - LEAD_SONGS).coerceIn(0, size - 1)
            return first until minOf(size, first + MAX_QUEUE)
        }
    }
}
