package com.theveloper.pixelplay.data.download.engine

import com.theveloper.pixelplay.data.database.CloudDownloadDao
import com.theveloper.pixelplay.data.database.CloudDownloadEntity
import com.theveloper.pixelplay.data.download.CloudDownloadSource
import com.theveloper.pixelplay.data.download.CloudDownloadSourceRegistry
import com.theveloper.pixelplay.data.download.CloudDownloadStateStore
import com.theveloper.pixelplay.data.download.model.CloudDownloadKey
import com.theveloper.pixelplay.data.download.model.CloudDownloadQuality
import com.theveloper.pixelplay.data.download.model.CloudDownloadState
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.BLOCKED
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.CANCELLING
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.COMPLETED
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.FAILED
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.MISSING
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.PENDING
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.QUEUED
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.RETRY_WAIT
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.RUNNING
import com.theveloper.pixelplay.data.download.model.CloudDownloadState.VERIFYING
import com.theveloper.pixelplay.data.download.model.CloudDownloadStateMachine
import com.theveloper.pixelplay.data.download.model.DownloadFileKey
import com.theveloper.pixelplay.data.download.model.DownloadStorageBackendId
import com.theveloper.pixelplay.data.download.model.StoredRef
import com.theveloper.pixelplay.data.download.storage.DownloadStorageBackend
import com.theveloper.pixelplay.data.download.storage.DownloadStorageRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Runs the cloud-download queue: at most [MAX_CONCURRENT_DOWNLOADS] transfers at once, backed
 * entirely by `cloud_downloads` — this class owns no queue state of its own that a killed
 * process could lose. Knows neither Jellyfin nor any other source: every source-specific fact
 * arrives through [CloudDownloadSource] and [com.theveloper.pixelplay.data.download.model.DownloadRequestSpec].
 *
 * **Entry point is [start].** It runs, in this fixed order, because getting the order wrong
 * has a real failure mode each time: the orphan sweep before anything else (a `.part` a
 * resumed download just started writing to would otherwise look identical to abandoned
 * garbage); the process-death reap before the queue starts consuming (a `RUNNING` row nobody
 * is actually running anymore needs to re-enter the retry cycle, not sit forever); the
 * completed-row health check before reporting anything as available (a file the user's OS
 * quietly removed, or a card that got ejected, has to be caught before something tries to
 * play it).
 *
 * **Known gap, not closed here:** [HttpFileDownloader] reports only a transfer's final
 * outcome, not incremental progress while it runs — so [CloudDownloadStateStore] only gets
 * updated at the coarse start/end points below, never with live in-flight byte counts. Closing
 * that needs a progress callback added to [HttpFileDownloader] itself, which is out of scope
 * here.
 */
@Singleton
class CloudDownloadEngine @Inject constructor(
    private val dao: CloudDownloadDao,
    private val sourceRegistry: CloudDownloadSourceRegistry,
    private val storageRegistry: DownloadStorageRegistry,
    private val downloader: HttpFileDownloader,
    private val stateStore: CloudDownloadStateStore,
) {

    /** Jobs currently transferring, keyed by `cloud_downloads.id` — what [requestCancel] cancels. */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    suspend fun start(nowMillis: Long = System.currentTimeMillis()) {
        sweepOrphans()
        reapStaleActiveRows(nowMillis)
        refreshCompletedRowHealth()
        runQueue(nowMillis)
    }

    /**
     * Deletes every file under a storage root that isn't a staging file this table still
     * considers live, or a published file a `COMPLETED` row still points at — covers both
     * "a `.part` whose row was deleted" and "a fully-written file that crashed before its
     * `COMPLETED` row was written": from a file's perspective on disk, they look identical.
     */
    suspend fun sweepOrphans() {
        val allRows = dao.getAll()
        val roots = allRows.map { it.storageBackend to it.storageRoot }.distinct()
        if (roots.isEmpty()) return

        val activeStagingPaths = dao.getStagingPathsForStates(NON_TERMINAL_STATE_NAMES)
        val publishedRefs = dao.getAllPublishedRefs()
        val knownRefs = (activeStagingPaths + publishedRefs).toSet()

        for ((backendIdRaw, root) in roots) {
            val backendId = resolveBackendId(backendIdRaw) ?: continue // unrecognized value; nothing safe to do with it
            val backend = storageRegistry.backendFor(backendId)
            backend.listOrphans(root, knownRefs).forEach { backend.delete(it) }
        }
    }

    /**
     * A row stuck in `RUNNING`/`VERIFYING` when the engine starts was never actually finished
     * by *this* process — the one that was transferring it died. Treated exactly like a failed
     * attempt (the same fork any other transient failure goes through), except the wait is
     * immediate: the process dying isn't a reason to make the user wait out a real backoff.
     */
    suspend fun reapStaleActiveRows(nowMillis: Long = System.currentTimeMillis()) {
        // errorCode/errorMessage are deliberately left as they were: stamping a synthetic
        // "process restart" reason here would erase a RANGE_NOT_SATISFIABLE marker from the
        // row's last real attempt, silently resetting handleFailure()'s consecutive-416 check
        // on every unrelated process restart.
        transitionAll(listOf(RUNNING.name, VERIFYING.name), RETRY_WAIT) {
            it.copy(attemptCount = it.attemptCount + 1, nextRetryAt = nowMillis)
        }
    }

    /**
     * A `COMPLETED` row whose file is gone self-heals differently depending on *why* it's
     * gone: a file actually missing (the user deleted it, some cleanup tool ran) re-queues on
     * its own — [MISSING] resolves straight back to [QUEUED]. A file that merely can't be
     * reached right now because its volume isn't mounted must not: re-downloading a file that
     * still exists, just on a card that's temporarily out, would leave two copies once the
     * card comes back. That case becomes [BLOCKED] instead, which never re-queues on its own.
     */
    suspend fun refreshCompletedRowHealth() {
        dao.getRowsInStates(listOf(COMPLETED.name)).forEach { row ->
            val ref = row.storageRef ?: return@forEach
            val backend = resolveBackend(row)
            if (backend == null) {
                transitionAndPersist(row, BLOCKED) {
                    it.copy(
                        errorCode = "UNKNOWN_BACKEND",
                        errorMessage = "storage_backend '${row.storageBackend}' is not recognized by this build",
                    )
                }
                return@forEach
            }

            if (backend.ensureReady(row.storageRoot).isFailure) {
                transitionAndPersist(row, BLOCKED) {
                    it.copy(errorCode = "UNMOUNTED", errorMessage = "Storage volume is not available")
                }
                return@forEach
            }
            if (!backend.exists(StoredRef(backend.id, ref))) {
                transitionAndPersist(row, MISSING) {
                    it.copy(errorCode = "FILE_MISSING", errorMessage = "The published file is no longer there")
                }
            }
        }
    }

    /**
     * One pass over everything currently actionable: promotes new/due rows, then launches as
     * many attempts as the global and per-source concurrency limits allow. Returns once every
     * attempt it launched has finished — never blocks waiting for a *future* `next_retry_at`;
     * whatever schedules repeated calls to this (a foreground service, a periodic worker) owns
     * waking the engine up again later, not this function.
     */
    suspend fun runQueue(nowMillis: Long = System.currentTimeMillis()): Unit = coroutineScope {
        promotePendingRows()
        promoteDueRetries(nowMillis)

        val globalSlots = Semaphore(MAX_CONCURRENT_DOWNLOADS)
        val perSourceInFlight = ConcurrentHashMap<Int, AtomicInteger>()
        val jobs = mutableListOf<Job>()

        for (row in dao.getRowsInStateByPriority(QUEUED.name)) {
            val source = sourceRegistry.sourceFor(row.sourceId)
            if (source == null) {
                transitionAndPersist(row, FAILED) {
                    it.copy(errorCode = "UNSUPPORTED_SOURCE", errorMessage = "No source registered for sourceId ${row.sourceId}")
                }
                continue
            }

            val cap = source.maxConcurrency(row.resolvedQuality())
            val counter = perSourceInFlight.getOrPut(row.sourceId) { AtomicInteger(0) }
            if (counter.get() >= cap) continue // this source is at capacity; try it again next pass
            if (!globalSlots.tryAcquire()) break // no global slots left this pass

            counter.incrementAndGet()
            val job = launch {
                try {
                    processOne(row, source, nowMillis)
                } finally {
                    counter.decrementAndGet()
                    globalSlots.release()
                    activeJobs.remove(row.id)
                }
            }
            activeJobs[row.id] = job
            jobs += job
        }

        jobs.joinAll()
    }

    /**
     * Cancels [downloadId] — its in-flight transfer if one is running, then its row and files.
     * Cancels the job **before** reading the row: [cancelAndJoin] doesn't return until the job
     * has fully finished (including any write [processOne] was mid-flight on), so the row this
     * reads afterward is guaranteed current — not a stale snapshot a concurrent write could
     * then clobber via this function's own whole-row update.
     */
    suspend fun requestCancel(downloadId: String) {
        activeJobs[downloadId]?.cancelAndJoin()
        val row = dao.getById(downloadId) ?: return
        transitionAndPersist(row, CANCELLING)

        val backend = resolveBackend(row)
        if (backend != null) {
            row.stagingPath?.let { backend.delete(StoredRef(backend.id, it)) }
            row.storageRef?.let { backend.delete(StoredRef(backend.id, it)) }
        } // else: can't clean up files for a backend this build doesn't recognize — the row
        // itself is still removed below; the files, if any, are picked up by a future
        // sweepOrphans() once the app version that recognizes this backend runs it.
        dao.deleteById(downloadId)
        stateStore.remove(CloudDownloadKey(row.sourceId, row.remoteId))
    }

    private suspend fun promotePendingRows() {
        // Unordered on purpose: runQueue() re-queries QUEUED rows by priority right after this
        // runs, so the order rows are *promoted* in here never affects the order they're
        // *processed* in.
        transitionAll(listOf(PENDING.name), QUEUED)
    }

    /**
     * A row whose wait is over gets requeued. A row whose `next_retry_at` implies a wait
     * *longer* than [MAX_BACKOFF_MILLIS] from right now gets that timestamp re-anchored to
     * `now + MAX_BACKOFF_MILLIS` instead — never promoted on the spot, since the ceiling is a
     * cap on how long the wait can be, not a reason to skip it. This is what actually prevents
     * a row from being stranded for years: a `next_retry_at` computed before the device clock
     * jumped (or was corrected) gets pulled back within reach on the very next pass, rather
     * than requiring the original, absurd duration to actually elapse.
     */
    private suspend fun promoteDueRetries(nowMillis: Long) {
        dao.getRowsInStates(listOf(RETRY_WAIT.name)).forEach { row ->
            val nextRetryAt = row.nextRetryAt ?: nowMillis
            when {
                nextRetryAt <= nowMillis -> transitionAndPersist(row, QUEUED)
                nextRetryAt - nowMillis > MAX_BACKOFF_MILLIS ->
                    dao.update(row.copy(nextRetryAt = nowMillis + MAX_BACKOFF_MILLIS))
            }
        }
    }

    private suspend fun processOne(pending: CloudDownloadEntity, source: CloudDownloadSource, nowMillis: Long) {
        val key = CloudDownloadKey(pending.sourceId, pending.remoteId)
        val row = transitionAndPersist(pending, RUNNING) {
            it.copy(startedAt = it.startedAt ?: nowMillis)
        }
        stateStore.update(key, RUNNING, downloadedBytes = 0L, expectedBytes = row.expectedBytes)

        val backend = resolveBackend(row)
        if (backend == null) {
            transitionAndPersist(row, FAILED) {
                it.copy(
                    errorCode = "UNKNOWN_BACKEND",
                    errorMessage = "storage_backend '${row.storageBackend}' is not recognized by this build",
                )
            }
            return
        }
        if (backend.ensureReady(row.storageRoot).isFailure) {
            transitionAndPersist(row, BLOCKED) {
                it.copy(errorCode = "UNMOUNTED", errorMessage = "Storage volume is not available")
            }
            return
        }

        val sourceSpec = source.buildDownloadRequest(row.remoteId, row.resolvedQuality()).getOrElse { error ->
            retryOrFail(row, reason = "REQUEST_FAILED", message = error.message ?: "buildDownloadRequest failed", nowMillis)
            return
        }
        // The source deliberately leaves expectedBytes null (it would mean re-fetching what a
        // batched preflight call already got) — the row's own persisted value is authoritative.
        val spec = sourceSpec.copy(expectedBytes = row.expectedBytes ?: sourceSpec.expectedBytes)

        val extension = row.container ?: spec.container ?: DEFAULT_EXTENSION
        val fileKey = DownloadFileKey(key, extension)
        val staging = backend.createStaging(row.storageRoot, fileKey).getOrElse { error ->
            retryOrFail(row, reason = "STAGING_FAILED", message = error.message ?: "createStaging failed", nowMillis)
            return
        }
        // Persisted immediately, before the transfer starts: sweepOrphans() and requestCancel()
        // both recognize a live staging file only through this column. Without it here, a
        // retried download's own .part file is indistinguishable from abandoned garbage the
        // very next time either of those runs.
        val staged = row.copy(stagingPath = staging.absolutePath)
        dao.update(staged)

        when (val outcome = downloader.download(staging, spec, resumeFromBytes = staging.length())) {
            is DownloadOutcome.Success -> completeDownload(staged, key, staging, fileKey, backend, outcome, nowMillis)
            is DownloadOutcome.Failure -> handleFailure(staged, outcome, nowMillis)
        }
    }

    private suspend fun completeDownload(
        row: CloudDownloadEntity,
        key: CloudDownloadKey,
        staging: File,
        fileKey: DownloadFileKey,
        backend: DownloadStorageBackend,
        outcome: DownloadOutcome.Success,
        nowMillis: Long,
    ) {
        val verifying = transitionAndPersist(row, VERIFYING) {
            it.copy(downloadedBytes = outcome.totalBytes, totalBytes = outcome.totalBytes)
        }
        val ref = backend.publish(staging, verifying.storageRoot, fileKey).getOrElse { error ->
            retryOrFail(verifying, reason = "PUBLISH_FAILED", message = error.message ?: "publish failed", nowMillis)
            return
        }
        transitionAndPersist(verifying, COMPLETED) {
            it.copy(storageRef = ref.value, stagingPath = null, completedAt = nowMillis)
        }
        stateStore.update(key, COMPLETED, outcome.totalBytes, outcome.totalBytes)
    }

    /**
     * The only two failure reasons that don't fall into the generic retry bucket:
     * [DownloadFailureReason.UNEXPECTED_CONTENT_TYPE] (an auth portal instead of audio — a
     * condition retrying blindly can't fix, and per the product invariant that never consumes
     * a retry attempt) becomes [BLOCKED]; a **second consecutive**
     * [DownloadFailureReason.RANGE_NOT_SATISFIABLE] (the first already triggered a clean
     * truncate-and-restart, so a second one on the fresh attempt means the server itself is
     * inconsistent about this file, not that the retry was unlucky) becomes terminal.
     */
    private suspend fun handleFailure(row: CloudDownloadEntity, failure: DownloadOutcome.Failure, nowMillis: Long) {
        when (failure.reason) {
            DownloadFailureReason.UNEXPECTED_CONTENT_TYPE -> {
                transitionAndPersist(row, BLOCKED) {
                    it.copy(errorCode = failure.reason.name, errorMessage = failure.message)
                }
            }
            DownloadFailureReason.RANGE_NOT_SATISFIABLE -> {
                if (row.errorCode == DownloadFailureReason.RANGE_NOT_SATISFIABLE.name) {
                    transitionAndPersist(row, FAILED) {
                        it.copy(
                            attemptCount = it.attemptCount + 1,
                            errorCode = "FAILED_CORRUPT",
                            errorMessage = failure.message,
                        )
                    }
                } else {
                    retryOrFail(row, reason = failure.reason.name, message = failure.message, nowMillis)
                }
            }
            else -> retryOrFail(row, reason = failure.reason.name, message = failure.message, nowMillis)
        }
    }

    private suspend fun retryOrFail(
        row: CloudDownloadEntity,
        reason: String,
        message: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val nextAttempt = row.attemptCount + 1
        transitionAndPersist(row, RETRY_WAIT) {
            it.copy(
                attemptCount = nextAttempt,
                nextRetryAt = nowMillis + backoffMillisFor(nextAttempt),
                errorCode = reason,
                errorMessage = message,
            )
        }
    }

    /** Exponential, doubling per attempt from [BASE_BACKOFF_MILLIS], never past [MAX_BACKOFF_MILLIS]. */
    private fun backoffMillisFor(attemptCount: Int): Long {
        val shift = (attemptCount - 1).coerceIn(0, 20) // guards the Long shift from overflowing
        return (BASE_BACKOFF_MILLIS shl shift).coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    /**
     * Applies [to] through [CloudDownloadStateMachine] (an illegal request degrades rather
     * than corrupting the row — see [CloudDownloadStateMachine.transition]), lets [extra]
     * change any other field, and persists the result in one write.
     */
    private suspend fun transitionAndPersist(
        row: CloudDownloadEntity,
        to: CloudDownloadState,
        extra: (CloudDownloadEntity) -> CloudDownloadEntity = { it },
    ): CloudDownloadEntity {
        val actual = CloudDownloadStateMachine.transition(CloudDownloadState.valueOf(row.state), to)
        val updated = extra(row.copy(state = actual.name))
        dao.update(updated)
        return updated
    }

    /** Every row currently in one of [states], moved to [to] (shared shape of
     * [promotePendingRows] and [reapStaleActiveRows] — anything that also branches to a
     * *different* target per row, like [refreshCompletedRowHealth], doesn't fit this shape). */
    private suspend fun transitionAll(
        states: List<String>,
        to: CloudDownloadState,
        extra: (CloudDownloadEntity) -> CloudDownloadEntity = { it },
    ) {
        dao.getRowsInStates(states).forEach { transitionAndPersist(it, to, extra) }
    }

    /**
     * `null`, not a thrown exception, for a `storage_backend` value this build doesn't
     * recognize — the same "a row a newer version wrote" scenario [CloudDownloadQuality
     * .fromCode] already documents for `quality`. [DownloadStorageRegistry.backendFor] itself
     * still throws for an unregistered *known* id (a wiring bug, not a runtime fact) — this is
     * only the string-to-enum step ahead of it, which is a data concern, not a wiring one.
     */
    private fun resolveBackend(row: CloudDownloadEntity): DownloadStorageBackend? =
        resolveBackendId(row.storageBackend)?.let { storageRegistry.backendFor(it) }

    private fun resolveBackendId(rawStorageBackend: String): DownloadStorageBackendId? =
        runCatching { DownloadStorageBackendId.valueOf(rawStorageBackend) }.getOrNull()

    private fun CloudDownloadEntity.resolvedQuality(): CloudDownloadQuality =
        CloudDownloadQuality.fromCode(quality) ?: CloudDownloadQuality.MAX

    private companion object {
        const val MAX_CONCURRENT_DOWNLOADS = 3
        const val BASE_BACKOFF_MILLIS = 2_000L
        const val MAX_BACKOFF_MILLIS = 15 * 60 * 1000L // 15 minutes
        const val DEFAULT_EXTENSION = "bin"
        val NON_TERMINAL_STATE_NAMES = CloudDownloadState.entries.filterNot { it.isTerminal }.map { it.name }
    }
}
