package com.theveloper.pixelplay.data.service.wear

import android.app.Application
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.di.AppScope
import com.theveloper.pixelplay.shared.WearCapabilities
import com.theveloper.pixelplay.shared.WearDataPaths
import com.theveloper.pixelplay.shared.WearPlaylistSync
import com.theveloper.pixelplay.shared.WearTransferProgress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Orchestrates sending a whole playlist to the watch: syncs the playlist's membership/order
 * first (so the watch can show it, and start playing it, before every song has arrived), then
 * transfers songs that aren't already on the watch one at a time — never in parallel, to avoid
 * saturating the single Bluetooth channel and spiking CPU/battery on the watch (see
 * [WatchAudioTranscoder]'s doc for why the encode itself is also sequential per song).
 *
 * Reuses the existing single-song pipeline end to end: [WatchAudioTranscoder] decides/produces
 * the audio to send, and [PhoneDirectWatchTransferCoordinator] still owns the actual chunked
 * ChannelClient streaming (via its [PhoneDirectWatchTransferCoordinator.WatchAudioOverride] hook)
 * and per-song cancellation.
 */
@Singleton
class PlaylistWatchTransferCoordinator @Inject constructor(
    private val application: Application,
    private val musicRepository: MusicRepository,
    private val watchAudioTranscoder: WatchAudioTranscoder,
    private val directTransferCoordinator: PhoneDirectWatchTransferCoordinator,
    private val wearPhoneTransferSender: WearPhoneTransferSender,
    private val transferStateStore: PhoneWatchTransferStateStore,
    // Injected directly (unlike most of this package, which resolves these via
    // Wearable.getXClient(application) internally) so this coordinator is constructible with
    // fakes in tests without needing to mock a static Java method.
    private val capabilityClient: CapabilityClient,
    private val messageClient: MessageClient,
    @AppScope private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cancelledBatchIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Deliberately an instance property, not a companion `const`/`var`: tests shrink it on their
     * own coordinator instance, so runs never leak a mutated timeout into unrelated tests the way
     * a shared static field would.
     */
    internal var songTransferAwaitTimeoutMs: Long = DEFAULT_SONG_TRANSFER_AWAIT_TIMEOUT_MS

    /** Returns the generated batchId immediately; the transfer itself runs asynchronously. */
    fun requestPlaylistTransfer(playlistId: String, playlistName: String, songIds: List<String>): String {
        val batchId = UUID.randomUUID().toString()
        if (songIds.isEmpty()) return batchId

        scope.launch {
            runBatchTransfer(batchId, playlistId, playlistName, songIds)
        }
        return batchId
    }

    fun cancelPlaylistTransfer(batchId: String) {
        cancelledBatchIds.add(batchId)
        val activeRequestId = transferStateStore.batchTransfers.value[batchId]?.activeRequestId
        if (activeRequestId != null) {
            scope.launch { wearPhoneTransferSender.cancelTransfer(activeRequestId) }
        }
        transferStateStore.markBatchCancelled(batchId)
    }

    private suspend fun runBatchTransfer(
        batchId: String,
        playlistId: String,
        playlistName: String,
        songIds: List<String>,
    ) {
        val nodes = resolveReachableNodes()
        transferStateStore.markBatchStarted(batchId, playlistId, playlistName, songIds.size)

        if (nodes.isEmpty()) {
            transferStateStore.markBatchFailed(batchId, "No reachable watch with PixelPlay")
            return
        }
        transferStateStore.retainReachableWatchNodes(nodes.map { it.id }.toSet())

        WatchTransferForegroundService.start(application)
        sendPlaylistSyncToNodes(nodes, playlistId, playlistName, songIds)

        val alreadyPresentCount = songIds.count { transferStateStore.isSongSavedOnAllReachableWatches(it) }
        repeat(alreadyPresentCount) { transferStateStore.markBatchSongCompleted(batchId) }

        val pendingSongIds = songIds.filterNot { transferStateStore.isSongSavedOnAllReachableWatches(it) }

        for (songId in pendingSongIds) {
            if (cancelledBatchIds.contains(batchId)) break

            val song = musicRepository.getSongsByIds(listOf(songId)).first().firstOrNull()
            if (song == null) {
                Timber.tag(TAG).w("Song not found for playlist transfer: songId=%s", songId)
                transferStateStore.markBatchSongFailed(batchId)
                continue
            }

            val outcome = transferSongToAllNodes(batchId, nodes, song)
            if (outcome.completed) {
                transferStateStore.markBatchSongCompleted(batchId)
            } else {
                transferStateStore.markBatchSongFailed(batchId, outcome.errorCode)
            }
        }

        cancelledBatchIds.remove(batchId)
        if (transferStateStore.batchTransfers.value[batchId]?.status != WearTransferProgress.STATUS_CANCELLED) {
            transferStateStore.markBatchCompleted(batchId)
        }
    }

    private suspend fun resolveReachableNodes(): List<Node> {
        return try {
            capabilityClient.getCapability(
                WearCapabilities.PIXELPLAY_WEAR_APP,
                CapabilityClient.FILTER_REACHABLE,
            ).await().nodes.toList()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Failed to resolve reachable watches for playlist transfer")
            emptyList()
        }
    }

    private suspend fun sendPlaylistSyncToNodes(
        nodes: List<Node>,
        playlistId: String,
        playlistName: String,
        songIds: List<String>,
    ) {
        val syncPayload = json.encodeToString(WearPlaylistSync(playlistId, playlistName, songIds))
            .toByteArray(Charsets.UTF_8)
        nodes.forEach { node ->
            try {
                messageClient.sendMessage(node.id, WearDataPaths.PLAYLIST_SYNC, syncPayload).await()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "Failed to send playlist sync to node=%s", node.id)
            }
        }
    }

    /** Transcodes [song] once (if needed) and streams it to every reachable [nodes] in turn. */
    private suspend fun transferSongToAllNodes(
        batchId: String,
        nodes: List<Node>,
        song: Song,
    ): SongTransferResult {
        if (cancelledBatchIds.contains(batchId)) return SongTransferResult(completed = false)

        val transcodeRequestId = UUID.randomUUID().toString()
        transferStateStore.markBatchSongStarted(batchId, transcodeRequestId, song.title)

        val transcodeResult = watchAudioTranscoder.transcodeIfNeeded(
            song = song,
            requestId = transcodeRequestId,
            onProgress = { fraction ->
                transferStateStore.markBatchSongProgress(
                    batchId,
                    WearTransferProgress.STATUS_TRANSCODING,
                    fraction.coerceIn(0f, 1f) * TRANSCODE_PHASE_WEIGHT,
                )
            },
        )
        if (transcodeResult is WatchAudioTranscoder.TranscodeResult.Failed) {
            Timber.tag(TAG).w(transcodeResult.error, "Transcode failed for songId=%s, skipping", song.id)
            return SongTransferResult(completed = false, errorCode = WearTransferProgress.ERROR_CODE_GENERIC)
        }
        if (cancelledBatchIds.contains(batchId)) {
            watchAudioTranscoder.cleanup(transcodeResult)
            return SongTransferResult(completed = false)
        }

        val audioOverride = (transcodeResult as? WatchAudioTranscoder.TranscodeResult.Transcoded)?.let { transcoded ->
            PhoneDirectWatchTransferCoordinator.WatchAudioOverride(
                file = transcoded.outputFile,
                mimeType = WatchAudioTranscoder.TRANSCODED_OUTPUT_MIME_TYPE,
                bitrateBps = WatchAudioTranscoder.TARGET_BITRATE_BPS,
            )
        }
        val wasTranscoded = audioOverride != null

        // Send to every reachable node (not just the first) — with multiple paired watches this
        // song should land on all of them. Present on at least one counts as done overall; if
        // every node failed, report whichever node failed last (good enough for the UI's
        // single-line failure summary).
        var succeededOnAnyNode = false
        var lastFailureErrorCode: String? = null
        for (node in nodes) {
            if (cancelledBatchIds.contains(batchId)) break
            val nodeOutcome = transferSongToNode(batchId, node, song, audioOverride, wasTranscoded)
            if (nodeOutcome.completed) {
                succeededOnAnyNode = true
            } else {
                lastFailureErrorCode = nodeOutcome.errorCode
            }
        }

        watchAudioTranscoder.cleanup(transcodeResult)
        return SongTransferResult(
            completed = succeededOnAnyNode,
            errorCode = if (succeededOnAnyNode) null else lastFailureErrorCode,
        )
    }

    private suspend fun transferSongToNode(
        batchId: String,
        node: Node,
        song: Song,
        audioOverride: PhoneDirectWatchTransferCoordinator.WatchAudioOverride?,
        wasTranscoded: Boolean,
    ): SongTransferResult {
        val requestId = UUID.randomUUID().toString()
        // Re-targets activeRequestId to this node's request without resetting the visible
        // progress: if the song was transcoded, it's already sitting at TRANSCODE_PHASE_WEIGHT.
        val startingProgress = if (wasTranscoded) TRANSCODE_PHASE_WEIGHT else 0f
        transferStateStore.markBatchSongStarted(batchId, requestId, song.title, startingProgress)

        val progressWatcherJob: Job = scope.launch {
            transferStateStore.transfers
                .mapNotNull { it[requestId] }
                .collect { state ->
                    if (state.status == WearTransferProgress.STATUS_TRANSFERRING) {
                        // Transferring is the second phase for a transcoded song: continue from
                        // TRANSCODE_PHASE_WEIGHT up to 1.0 instead of restarting at 0.
                        val overallProgress = if (wasTranscoded) {
                            TRANSCODE_PHASE_WEIGHT + state.progress * (1f - TRANSCODE_PHASE_WEIGHT)
                        } else {
                            state.progress
                        }
                        transferStateStore.markBatchSongProgress(batchId, state.status, overallProgress)
                    }
                }
        }

        directTransferCoordinator.startTransferToWatch(
            nodeId = node.id,
            requestId = requestId,
            songId = song.id,
            audioOverride = audioOverride,
        )

        val finalState = withTimeoutOrNull(songTransferAwaitTimeoutMs) {
            transferStateStore.transfers
                .mapNotNull { it[requestId] }
                .first { it.status in TERMINAL_STATUSES }
        }
        progressWatcherJob.cancel()

        if (finalState == null) {
            Timber.tag(TAG).w(
                "Timed out awaiting watch confirmation: songId=%s requestId=%s",
                song.id,
                requestId,
            )
            transferStateStore.markProgress(
                requestId = requestId,
                songId = song.id,
                bytesTransferred = 0L,
                totalBytes = 0L,
                status = WearTransferProgress.STATUS_FAILED,
                error = "Timed out waiting for watch confirmation",
            )
            return SongTransferResult(completed = false, errorCode = WearTransferProgress.ERROR_CODE_TIMED_OUT)
        }

        return SongTransferResult(
            completed = finalState.status == WearTransferProgress.STATUS_COMPLETED,
            errorCode = if (finalState.status == WearTransferProgress.STATUS_FAILED) {
                WearTransferProgress.ERROR_CODE_GENERIC
            } else {
                null
            },
        )
    }

    private data class SongTransferResult(val completed: Boolean, val errorCode: String? = null)

    internal companion object {
        private const val TAG = "PlaylistWatchTransfer"

        // Transcoding and transferring both report 0f..1f progress for the same song; weighting
        // them into one continuous 0..1 scale (instead of each resetting to 0) avoids the visible
        // jump-then-reset when a song moves from one phase to the other.
        private const val TRANSCODE_PHASE_WEIGHT = 0.3f

        // Deliberately generous relative to the watch's own idle watchdog: leaves room for slow
        // transcoding plus a slow Bluetooth link on large files. Better to wait too long than to
        // mark a legitimately-slow transfer as failed.
        private const val DEFAULT_SONG_TRANSFER_AWAIT_TIMEOUT_MS = 300_000L

        private val TERMINAL_STATUSES = setOf(
            WearTransferProgress.STATUS_COMPLETED,
            WearTransferProgress.STATUS_FAILED,
            WearTransferProgress.STATUS_CANCELLED,
        )
    }
}
