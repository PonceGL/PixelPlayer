package com.theveloper.pixelplay.data.download

import android.os.SystemClock
import com.theveloper.pixelplay.data.download.model.CloudDownloadKey
import com.theveloper.pixelplay.data.download.model.CloudDownloadProgress
import com.theveloper.pixelplay.data.download.model.CloudDownloadState
import com.theveloper.pixelplay.data.download.model.CollectionDownloadProgress
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How far back speed/ETA look — a sliding window, not the download's cumulative average. */
private const val SPEED_WINDOW_MILLIS = 5_000L

/**
 * The live progress of every in-flight cloud download, entirely in memory:
 * `cloud_downloads` never sees a write from this class, structurally — it has no `DAO`
 * dependency to write one with. Only the download engine persists a coarse resume point.
 *
 * Shape calcada de `PhoneWatchTransferStateStore` (`@Singleton`, `MutableStateFlow` privados +
 * `asStateFlow()` públicos). Two differences from that precedent, both
 * deliberate: no internal `CoroutineScope` — nothing here launches background work, so there
 * is none to own — and terminal entries are **not** auto-removed after a visibility delay:
 * `cloud_downloads`' own `state` column is the durable "is this done" answer once a row
 * exists, so this store keeps whatever [update] last reported until a caller explicitly
 * [remove]s it (the row is gone), rather than guessing when it's safe to forget.
 */
@Singleton
class CloudDownloadStateStore @Inject constructor() {

    private val _progressByDownloadId = MutableStateFlow<Map<String, CloudDownloadProgress>>(emptyMap())
    val progressByDownloadId: StateFlow<Map<String, CloudDownloadProgress>> =
        _progressByDownloadId.asStateFlow()

    private val _progressBySubscriptionId = MutableStateFlow<Map<String, CollectionDownloadProgress>>(emptyMap())
    val progressBySubscriptionId: StateFlow<Map<String, CollectionDownloadProgress>> =
        _progressBySubscriptionId.asStateFlow()

    private val _completedDownloads = MutableSharedFlow<CloudDownloadKey>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits once per transition **into** [CloudDownloadState.COMPLETED] — never on a repeat. */
    val completedDownloads: SharedFlow<CloudDownloadKey> = _completedDownloads.asSharedFlow()

    private val samplesByDownloadId = ConcurrentHashMap<String, ArrayDeque<ProgressSample>>()
    private val subscriptionMembers = ConcurrentHashMap<String, Set<String>>()

    /**
     * Records a progress tick for [key]. [nowMillis] defaults to a monotonic clock
     * ([SystemClock.elapsedRealtime]) rather than wall-clock time, so a device clock change
     * mid-download can't produce an absurd speed or ETA (overridable in tests).
     */
    fun update(
        key: CloudDownloadKey,
        state: CloudDownloadState,
        downloadedBytes: Long,
        expectedBytes: Long?,
        nowMillis: Long = SystemClock.elapsedRealtime(),
    ) {
        val downloadId = key.storageId
        val wasAlreadyCompleted = _progressByDownloadId.value[downloadId]?.state == CloudDownloadState.COMPLETED
        val (bytesPerSecond, etaMillis) = speedAndEta(downloadId, downloadedBytes, expectedBytes, state, nowMillis)

        _progressByDownloadId.update { map ->
            map + (downloadId to CloudDownloadProgress(
                key = key,
                state = state,
                downloadedBytes = downloadedBytes,
                expectedBytes = expectedBytes,
                bytesPerSecond = bytesPerSecond,
                etaMillis = etaMillis,
            ))
        }

        if (state == CloudDownloadState.COMPLETED && !wasAlreadyCompleted) {
            _completedDownloads.tryEmit(key)
        }
        recomputeSubscriptionsContaining(downloadId)
    }

    /**
     * Drops [key]'s live progress. The only way an entry leaves [progressByDownloadId] — no
     * automatic expiry. Safe to call for a key that was never tracked, or was only ever
     * updated once and cancelled before a second tick: either way, no entry remains.
     */
    fun remove(key: CloudDownloadKey) {
        val downloadId = key.storageId
        samplesByDownloadId.remove(downloadId)
        _progressByDownloadId.update { it - downloadId }
        recomputeSubscriptionsContaining(downloadId)
    }

    /**
     * Declares which download ids belong to [subscriptionId], so [progressBySubscriptionId]
     * can aggregate them. Collection downloads are the first real caller; nothing here depends
     * on that feature existing yet.
     */
    fun registerSubscriptionMembers(subscriptionId: String, downloadIds: Set<String>) {
        subscriptionMembers[subscriptionId] = downloadIds
        recomputeSubscription(subscriptionId)
    }

    /** Stops tracking [subscriptionId] — e.g. the subscription itself was removed. */
    fun unregisterSubscription(subscriptionId: String) {
        subscriptionMembers.remove(subscriptionId)
        _progressBySubscriptionId.update { it - subscriptionId }
    }

    private fun recomputeSubscriptionsContaining(downloadId: String) {
        subscriptionMembers.forEach { (subscriptionId, members) ->
            if (downloadId in members) recomputeSubscription(subscriptionId)
        }
    }

    private fun recomputeSubscription(subscriptionId: String) {
        val members = subscriptionMembers[subscriptionId] ?: return
        val currentProgress = _progressByDownloadId.value
        val memberProgress = members.mapNotNull { currentProgress[it] }
        val completedCount = memberProgress.count { it.state == CloudDownloadState.COMPLETED }
        val isRunning = memberProgress.any { !it.state.isTerminal }

        _progressBySubscriptionId.update { map ->
            map + (subscriptionId to CollectionDownloadProgress(
                isRunning = isRunning,
                currentCount = completedCount,
                totalCount = members.size,
                isCompleted = members.isNotEmpty() && completedCount == members.size,
            ))
        }
    }

    /**
     * Speed over the trailing [SPEED_WINDOW_MILLIS] and the ETA it implies.
     *
     * - Terminal [state] (paused, blocked, failed, ...): `0` B/s — a real, reportable value —
     *   and a `null` ETA, never `NaN`/`Infinity`.
     * - Fewer than two samples in the window yet (a download that just started): both `null`
     *   — genuinely unknown, which is a different fact from "known to be zero".
     * - Otherwise: `bytesDelta / elapsedMillis` over the window, and, when [expectedBytes] is
     *   known, `remainingBytes / bytesPerSecond`.
     */
    private fun speedAndEta(
        downloadId: String,
        downloadedBytes: Long,
        expectedBytes: Long?,
        state: CloudDownloadState,
        nowMillis: Long,
    ): Pair<Long?, Long?> {
        val window = samplesByDownloadId.getOrPut(downloadId) { ArrayDeque() }
        synchronized(window) {
            window.addLast(ProgressSample(nowMillis, downloadedBytes))
            while (window.size > 1 && nowMillis - window.first().atMillis > SPEED_WINDOW_MILLIS) {
                window.removeFirst()
            }

            if (state.isTerminal) return 0L to null
            if (window.size < 2) return null to null

            val oldest = window.first()
            val newest = window.last()
            val elapsedMillis = newest.atMillis - oldest.atMillis
            if (elapsedMillis <= 0) return null to null

            val bytesDelta = newest.downloadedBytes - oldest.downloadedBytes
            if (bytesDelta <= 0) return 0L to null

            val bytesPerSecond = bytesDelta * 1000 / elapsedMillis
            val etaMillis = expectedBytes?.let { expected ->
                val remaining = expected - downloadedBytes
                if (remaining <= 0) 0L else remaining * 1000 / bytesPerSecond
            }
            return bytesPerSecond to etaMillis
        }
    }

    private data class ProgressSample(val atMillis: Long, val downloadedBytes: Long)
}
