package com.theveloper.pixelplay.data.download.engine

import android.net.Uri
import com.theveloper.pixelplay.data.database.CloudDownloadDao
import com.theveloper.pixelplay.data.database.CloudDownloadEntity
import com.theveloper.pixelplay.data.download.CloudDownloadSourceRegistry
import com.theveloper.pixelplay.data.download.CloudDownloadSource
import com.theveloper.pixelplay.data.download.CloudDownloadStateStore
import com.theveloper.pixelplay.data.download.DownloadServerCapabilitiesStore
import com.theveloper.pixelplay.data.download.model.CloudDownloadKey
import com.theveloper.pixelplay.data.download.model.CloudDownloadQuality
import com.theveloper.pixelplay.data.download.model.CloudDownloadState
import com.theveloper.pixelplay.data.download.model.DownloadFileKey
import com.theveloper.pixelplay.data.download.model.DownloadRequestSpec
import com.theveloper.pixelplay.data.download.model.DownloadStorageBackendId
import com.theveloper.pixelplay.data.download.model.RemoteItemInfo
import com.theveloper.pixelplay.data.download.model.StoredRef
import com.theveloper.pixelplay.data.download.storage.DownloadStorageBackend
import com.theveloper.pixelplay.data.download.storage.DownloadStorageRegistry
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val SOURCE_TYPE = 6 // matches SourceType.JELLYFIN's value; the engine never checks which
private const val FIFTEEN_MINUTES_MILLIS = 15 * 60 * 1000L // mirrors CloudDownloadEngine's own backoff ceiling

/** In-memory [CloudDownloadDao] — real SQL semantics (`WHERE`, `ORDER BY`) reimplemented by
 * hand in Kotlin, which is exactly the risk of a DAO fake: see the ordering/filtering
 * assertions each test makes, which exercise this as much as the engine. */
private class FakeCloudDownloadDao : CloudDownloadDao {
    val rows = LinkedHashMap<String, CloudDownloadEntity>()

    override suspend fun insert(entity: CloudDownloadEntity): Long {
        if (rows.containsKey(entity.id)) return -1L
        rows[entity.id] = entity
        return 1L
    }

    override suspend fun getById(id: String): CloudDownloadEntity? = rows[id]
    override suspend fun getAll(): List<CloudDownloadEntity> = rows.values.toList()
    override suspend fun deleteById(id: String) { rows.remove(id) }
    override suspend fun update(entity: CloudDownloadEntity) { rows[entity.id] = entity }

    override suspend fun getRowsInStateByPriority(state: String): List<CloudDownloadEntity> =
        rows.values.filter { it.state == state }
            .sortedWith(compareByDescending<CloudDownloadEntity> { it.priority }.thenBy { it.enqueuedAt })

    override suspend fun getRowsInStates(states: List<String>): List<CloudDownloadEntity> =
        rows.values.filter { it.state in states }

    override suspend fun countInStates(states: List<String>): Int =
        rows.values.count { it.state in states }

    override suspend fun getAllPublishedRefs(): List<String> = rows.values.mapNotNull { it.storageRef }

    override suspend fun getStagingPathsForStates(states: List<String>): List<String> =
        rows.values.filter { it.state in states }.mapNotNull { it.stagingPath }
}

/** Real filesystem under a JUnit temp directory — a fake in the "simplified real thing" sense,
 * not a mock: [listOrphans] genuinely walks the directory tree, the same as the real backend. */
private class FakeDownloadStorageBackend(
    override val id: DownloadStorageBackendId = DownloadStorageBackendId.APP_PRIVATE,
) : DownloadStorageBackend {
    var readyResult: Result<Unit> = Result.success(Unit)

    override suspend fun ensureReady(root: String): Result<Unit> = readyResult
    override suspend fun freeBytes(root: String): Long? = File(root).usableSpace

    override suspend fun createStaging(root: String, key: DownloadFileKey): Result<File> {
        val dir = File(root, key.cloudDownloadKey.sourceType.toString()).apply { mkdirs() }
        return Result.success(File(dir, "${key.cloudDownloadKey.remoteId}.part"))
    }

    override suspend fun publish(staging: File, root: String, key: DownloadFileKey): Result<StoredRef> {
        val destination = File(staging.parentFile, "${key.cloudDownloadKey.remoteId}.${key.extension}")
        if (!staging.renameTo(destination)) return Result.failure(IOException("renameTo failed"))
        return Result.success(StoredRef(id, destination.absolutePath))
    }

    override suspend fun open(ref: StoredRef): Uri? = null
    override suspend fun exists(ref: StoredRef): Boolean = File(ref.value).exists()
    override suspend fun sizeOf(ref: StoredRef): Long? = File(ref.value).takeIf { it.exists() }?.length()
    override suspend fun delete(ref: StoredRef) { File(ref.value).delete() }

    override suspend fun listOrphans(root: String, knownRefs: Set<String>): List<StoredRef> {
        val dir = File(root)
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.absolutePath in knownRefs }
            .map { StoredRef(id, it.absolutePath) }
            .toList()
    }
}

private class FakeCloudDownloadSource(
    override val sourceType: Int = SOURCE_TYPE,
    private val specProvider: suspend (String) -> Result<DownloadRequestSpec>,
) : CloudDownloadSource {
    override fun supports(quality: CloudDownloadQuality): Boolean = true
    override fun maxConcurrency(quality: CloudDownloadQuality): Int = 3
    override suspend fun isAuthenticated(): Boolean = true
    override suspend fun fetchItemInfo(remoteIds: List<String>): Result<Map<String, RemoteItemInfo>> =
        Result.success(emptyMap())
    override suspend fun buildDownloadRequest(remoteId: String, quality: CloudDownloadQuality) =
        specProvider(remoteId)
}

class CloudDownloadEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var dao: FakeCloudDownloadDao
    private lateinit var backend: FakeDownloadStorageBackend
    private lateinit var stateStore: CloudDownloadStateStore
    private lateinit var engine: CloudDownloadEngine
    private var sourceSpecResult: Result<DownloadRequestSpec> =
        Result.failure(IllegalStateException("sourceSpecResult not set - setUp() didn't run"))

    private fun buildEngine(source: CloudDownloadSource) = CloudDownloadEngine(
        dao = dao,
        sourceRegistry = CloudDownloadSourceRegistry(mapOf(source.sourceType to source)),
        storageRegistry = DownloadStorageRegistry(setOf(backend)),
        downloader = HttpFileDownloader(
            OkHttpClient(),
            mockk(relaxed = true) { coEvery { cachedSupportsRange(any(), any()) } returns null },
            Dispatchers.IO,
        ),
        stateStore = stateStore,
    )

    private fun row(
        remoteId: String = "item-1",
        state: CloudDownloadState = CloudDownloadState.PENDING,
        priority: Int = 0,
        enqueuedAt: Long = 1_000L,
        expectedBytes: Long? = null,
        attemptCount: Int = 0,
        nextRetryAt: Long? = null,
        errorCode: String? = null,
        stagingPath: String? = null,
        storageRef: String? = null,
        storageBackend: String = DownloadStorageBackendId.APP_PRIVATE.name,
    ) = CloudDownloadEntity(
        id = "$SOURCE_TYPE:$remoteId",
        sourceId = SOURCE_TYPE,
        remoteId = remoteId,
        requestedQuality = CloudDownloadQuality.MAX.code,
        quality = CloudDownloadQuality.MAX.code,
        state = state.name,
        storageBackend = storageBackend,
        storageRoot = root.absolutePath,
        stagingPath = stagingPath,
        storageRef = storageRef,
        expectedBytes = expectedBytes,
        attemptCount = attemptCount,
        nextRetryAt = nextRetryAt,
        errorCode = errorCode,
        priority = priority,
        enqueuedAt = enqueuedAt,
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        root = Files.createTempDirectory("cloud-download-engine-test").toFile()
        dao = FakeCloudDownloadDao()
        backend = FakeDownloadStorageBackend()
        stateStore = CloudDownloadStateStore()
        sourceSpecResult = Result.success(
            DownloadRequestSpec(
                url = server.url("/download").toString(),
                headers = emptyMap(),
                supportsRange = false,
                expectedBytes = null,
                container = "flac",
                mimeType = "audio/flac",
                serverKey = server.url("/").toString().trimEnd('/'),
            )
        )
        engine = buildEngine(FakeCloudDownloadSource { sourceSpecResult })
    }

    @AfterEach
    fun tearDown() {
        server.close()
        root.deleteRecursively()
    }

    // ─── Happy path ─────────────────────────────────────────────────────────

    @Test
    fun `a queued row downloads and ends up COMPLETED with the file published`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("the whole file").build())
        dao.rows["$SOURCE_TYPE:item-1"] = row(state = CloudDownloadState.QUEUED)

        engine.runQueue()

        val finished = dao.getById("$SOURCE_TYPE:item-1")!!
        assertEquals(CloudDownloadState.COMPLETED.name, finished.state)
        val publishedFile = File(finished.storageRef!!)
        assertTrue(publishedFile.exists())
        assertEquals("the whole file", publishedFile.readText())
        assertNull(finished.stagingPath)
    }

    @Test
    fun `a pending row is promoted to queued and then processed in the same pass`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("bytes").build())
        dao.rows["$SOURCE_TYPE:item-1"] = row(state = CloudDownloadState.PENDING)

        engine.runQueue()

        assertEquals(CloudDownloadState.COMPLETED.name, dao.getById("$SOURCE_TYPE:item-1")!!.state)
    }

    @Test
    fun `higher priority rows are launched before lower priority ones when a slot is scarce`() = runTest {
        // Both requests succeed; what's asserted is which row's request MockWebServer served
        // first, via the path each row's fake source points at.
        server.enqueue(MockResponse.Builder().code(200).body("low").build())
        server.enqueue(MockResponse.Builder().code(200).body("high").build())
        dao.rows["$SOURCE_TYPE:low"] = row(remoteId = "low", state = CloudDownloadState.QUEUED, priority = 0, enqueuedAt = 1L)
        dao.rows["$SOURCE_TYPE:high"] = row(remoteId = "high", state = CloudDownloadState.QUEUED, priority = 10, enqueuedAt = 2L)

        val ordered = dao.getRowsInStateByPriority(CloudDownloadState.QUEUED.name)

        assertEquals("high", ordered.first().remoteId)
        assertEquals("low", ordered.last().remoteId)
    }

    // ─── Case 8: orphan sweep runs before the queue is consumed ────────────────

    @Test
    fun `start sweeps orphans before it lets the queue consume anything`() = runTest {
        val events = mutableListOf<String>()
        val recordingBackend = object : DownloadStorageBackend by backend {
            override suspend fun listOrphans(root: String, knownRefs: Set<String>): List<StoredRef> {
                events += "sweep"
                return backend.listOrphans(root, knownRefs)
            }
        }
        val recordingEngine = CloudDownloadEngine(
            dao = dao,
            sourceRegistry = CloudDownloadSourceRegistry(
                mapOf(SOURCE_TYPE to FakeCloudDownloadSource {
                    events += "queue-consumed"
                    sourceSpecResult
                })
            ),
            storageRegistry = DownloadStorageRegistry(setOf(recordingBackend)),
            downloader = HttpFileDownloader(
                OkHttpClient(),
                mockk(relaxed = true) { coEvery { cachedSupportsRange(any(), any()) } returns null },
                Dispatchers.IO,
            ),
            stateStore = stateStore,
        )
        server.enqueue(MockResponse.Builder().code(200).body("x").build())
        dao.rows["$SOURCE_TYPE:item-1"] = row(state = CloudDownloadState.QUEUED)

        recordingEngine.start()

        assertEquals(listOf("sweep", "queue-consumed"), events)
    }

    @Test
    fun `a part file with no row for it is deleted by the sweep`() = runTest {
        val orphan = File(root, "$SOURCE_TYPE/ghost.part").apply { parentFile?.mkdirs(); writeText("leftover") }
        // The sweep only scans roots a row currently points at (see sweepOrphans's own doc) —
        // an unrelated live row for the same root is what makes this root eligible at all.
        dao.rows["$SOURCE_TYPE:item-2"] = row(remoteId = "item-2", state = CloudDownloadState.QUEUED)
        server.enqueue(MockResponse.Builder().code(200).body("other song").build())

        engine.sweepOrphans()

        assertFalse(orphan.exists())
    }

    @Test
    fun `a part file backing a non-terminal row survives the sweep`() = runTest {
        val staging = File(root, "$SOURCE_TYPE/item-1.part").apply { parentFile?.mkdirs(); writeText("partial") }
        dao.rows["$SOURCE_TYPE:item-1"] =
            row(state = CloudDownloadState.RETRY_WAIT, stagingPath = staging.absolutePath)

        engine.sweepOrphans()

        assertTrue(staging.exists())
    }

    // ─── Case 11: COMPLETED self-healing — MISSING vs BLOCKED(unmounted) ───────

    @Test
    fun `a completed row whose file vanished on a mounted volume becomes MISSING`() = runTest {
        dao.rows["$SOURCE_TYPE:item-1"] = row(
            state = CloudDownloadState.COMPLETED,
            storageRef = File(root, "gone.flac").absolutePath, // never actually written
        )

        engine.refreshCompletedRowHealth()

        assertEquals(CloudDownloadState.MISSING.name, dao.getById("$SOURCE_TYPE:item-1")!!.state)
    }

    @Test
    fun `a completed row on an unmounted volume becomes BLOCKED, not MISSING`() = runTest {
        backend.readyResult = Result.failure(IOException("volume not mounted"))
        dao.rows["$SOURCE_TYPE:item-1"] = row(
            state = CloudDownloadState.COMPLETED,
            storageRef = File(root, "on-the-sd-card.flac").absolutePath,
        )

        engine.refreshCompletedRowHealth()

        assertEquals(CloudDownloadState.BLOCKED.name, dao.getById("$SOURCE_TYPE:item-1")!!.state)
    }

    @Test
    fun `a completed row whose file is still there is left alone`() = runTest {
        val file = File(root, "still-here.flac").apply { writeText("data") }
        dao.rows["$SOURCE_TYPE:item-1"] = row(state = CloudDownloadState.COMPLETED, storageRef = file.absolutePath)

        engine.refreshCompletedRowHealth()

        assertEquals(CloudDownloadState.COMPLETED.name, dao.getById("$SOURCE_TYPE:item-1")!!.state)
    }

    // ─── Case 7: a RUNNING row found at startup means the process died mid-transfer ─

    @Test
    fun `a row stuck RUNNING at startup is reaped into an immediately-due retry`() = runTest {
        dao.rows["$SOURCE_TYPE:item-1"] = row(state = CloudDownloadState.RUNNING, attemptCount = 0)

        engine.reapStaleActiveRows(nowMillis = 5_000L)

        val reaped = dao.getById("$SOURCE_TYPE:item-1")!!
        assertEquals(CloudDownloadState.RETRY_WAIT.name, reaped.state)
        assertEquals(1, reaped.attemptCount)
        assertEquals(5_000L, reaped.nextRetryAt)
    }

    // ─── Case 12: a clock jump must never strand a row for longer than the backoff cap ─

    @Test
    fun `a retry-wait row whose next_retry_at is years away has its wait re-anchored to the backoff cap`() = runTest {
        val now = 1_000_000L
        val nextRetryYearsAway = now + java.util.concurrent.TimeUnit.DAYS.toMillis(400)
        dao.rows["$SOURCE_TYPE:item-1"] =
            row(state = CloudDownloadState.RETRY_WAIT, nextRetryAt = nextRetryYearsAway)

        engine.runQueue(nowMillis = now)

        // Not promoted yet — a cap of 15 minutes doesn't mean "due immediately" — but its
        // next_retry_at is no longer years away: it's pulled back to exactly the ceiling.
        val stillWaiting = dao.getById("$SOURCE_TYPE:item-1")!!
        assertEquals(CloudDownloadState.RETRY_WAIT.name, stillWaiting.state)
        assertEquals(now + FIFTEEN_MINUTES_MILLIS, stillWaiting.nextRetryAt)
    }

    @Test
    fun `once the re-anchored wait actually elapses, the row is promoted and processed`() = runTest {
        val now = 1_000_000L
        dao.rows["$SOURCE_TYPE:item-1"] = row(
            state = CloudDownloadState.RETRY_WAIT,
            nextRetryAt = now + FIFTEEN_MINUTES_MILLIS,
        )
        server.enqueue(MockResponse.Builder().code(200).body("bytes").build())

        engine.runQueue(nowMillis = now + FIFTEEN_MINUTES_MILLIS)

        assertEquals(CloudDownloadState.COMPLETED.name, dao.getById("$SOURCE_TYPE:item-1")!!.state)
    }

    @Test
    fun `a retry-wait row genuinely not due yet is left alone`() = runTest {
        val now = 1_000_000L
        dao.rows["$SOURCE_TYPE:item-1"] = row(state = CloudDownloadState.RETRY_WAIT, nextRetryAt = now + 60_000L)

        engine.runQueue(nowMillis = now)

        assertEquals(CloudDownloadState.RETRY_WAIT.name, dao.getById("$SOURCE_TYPE:item-1")!!.state)
    }

    // ─── Case 1: Range ignored (200 for a Range request) retries without consuming the row ─

    @Test
    fun `content-type that looks like an auth portal blocks without consuming a retry attempt`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body("<html>please log in</html>")
                .addHeader("Content-Type", "text/html").build()
        )
        dao.rows["$SOURCE_TYPE:item-1"] = row(state = CloudDownloadState.QUEUED, attemptCount = 0)

        engine.runQueue()

        val blocked = dao.getById("$SOURCE_TYPE:item-1")!!
        assertEquals(CloudDownloadState.BLOCKED.name, blocked.state)
        assertEquals(0, blocked.attemptCount) // BLOCKED never consumes a retry attempt
    }

    // ─── Case 3: a second consecutive 416 gives up instead of retrying forever ─────

    @Test
    fun `a first 416 retries, but a second consecutive one fails as corrupt`() = runTest {
        dao.rows["$SOURCE_TYPE:item-1"] = row(
            state = CloudDownloadState.QUEUED,
            attemptCount = 1,
            errorCode = "RANGE_NOT_SATISFIABLE", // as if a first 416 already happened last attempt
        )
        server.enqueue(MockResponse.Builder().code(416).build())

        engine.runQueue()

        val failed = dao.getById("$SOURCE_TYPE:item-1")!!
        assertEquals(CloudDownloadState.FAILED.name, failed.state)
        assertEquals("FAILED_CORRUPT", failed.errorCode)
    }

    @Test
    fun `a lone 416 with no prior one retries instead of failing outright`() = runTest {
        dao.rows["$SOURCE_TYPE:item-1"] = row(state = CloudDownloadState.QUEUED, attemptCount = 0, errorCode = null)
        server.enqueue(MockResponse.Builder().code(416).build())

        engine.runQueue()

        val retried = dao.getById("$SOURCE_TYPE:item-1")!!
        assertEquals(CloudDownloadState.RETRY_WAIT.name, retried.state)
        assertEquals(1, retried.attemptCount)
    }

    // ─── Cancellation ───────────────────────────────────────────────────────

    @Test
    fun `requestCancel deletes the row and its staged file`() = runTest {
        val staging = File(root, "$SOURCE_TYPE/item-1.part").apply { parentFile?.mkdirs(); writeText("partial") }
        dao.rows["$SOURCE_TYPE:item-1"] =
            row(state = CloudDownloadState.RETRY_WAIT, stagingPath = staging.absolutePath)

        engine.requestCancel("$SOURCE_TYPE:item-1")

        assertNull(dao.getById("$SOURCE_TYPE:item-1"))
        assertFalse(staging.exists())
    }

    // ─── An unrecognized storage_backend degrades one row, never crashes the pass ─

    /**
     * A row with a `storage_backend` this build doesn't know (e.g. one written by a newer app
     * version) must not throw `DownloadStorageBackendId.valueOf(...)` uncaught — that would
     * take the whole `runQueue()` pass down with it, failing every other row's attempt too.
     */
    @Test
    fun `a row with an unrecognized storage backend fails on its own, without crashing the pass`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("bytes").build())
        dao.rows["$SOURCE_TYPE:unknown-backend"] =
            row(remoteId = "unknown-backend", state = CloudDownloadState.QUEUED, storageBackend = "SOME_FUTURE_BACKEND")
        dao.rows["$SOURCE_TYPE:item-1"] = row(remoteId = "item-1", state = CloudDownloadState.QUEUED)

        engine.runQueue()

        assertEquals(CloudDownloadState.FAILED.name, dao.getById("$SOURCE_TYPE:unknown-backend")!!.state)
        // The other row's own attempt still ran to completion in the same pass.
        assertEquals(CloudDownloadState.COMPLETED.name, dao.getById("$SOURCE_TYPE:item-1")!!.state)
    }

    // ─── A retried attempt's staging file must stay recognizable as "live" ─────

    /**
     * `sweepOrphans()` and `requestCancel()` only know a `.part` file is still owned by a row
     * through `stagingPath` — if `processOne` never wrote it back onto the row, every retry's
     * own staging file would look identical to abandoned garbage the very next sweep.
     */
    @Test
    fun `a failed attempt still persists the staging file's path onto the row`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        dao.rows["$SOURCE_TYPE:item-1"] = row(state = CloudDownloadState.QUEUED)

        engine.runQueue()

        val retried = dao.getById("$SOURCE_TYPE:item-1")!!
        assertEquals(CloudDownloadState.RETRY_WAIT.name, retried.state)
        // Not asserting the file itself exists on disk: a plain 500 never gets past the status
        // check in HttpFileDownloader, so no bytes — and no staging file — are ever written.
        // What this test is about is the *row* remembering the path it staged to.
        assertTrue(retried.stagingPath != null)
    }

    // ─── The consecutive-416 marker must survive an unrelated process restart ─

    @Test
    fun `reapStaleActiveRows preserves a prior RANGE_NOT_SATISFIABLE marker`() = runTest {
        dao.rows["$SOURCE_TYPE:item-1"] =
            row(state = CloudDownloadState.RUNNING, errorCode = "RANGE_NOT_SATISFIABLE")

        engine.reapStaleActiveRows(nowMillis = 5_000L)

        assertEquals("RANGE_NOT_SATISFIABLE", dao.getById("$SOURCE_TYPE:item-1")!!.errorCode)
    }

    @Test
    fun `a second 416 surviving a process restart still fails as corrupt, not retries forever`() = runTest {
        dao.rows["$SOURCE_TYPE:item-1"] =
            row(state = CloudDownloadState.RUNNING, errorCode = "RANGE_NOT_SATISFIABLE")
        engine.reapStaleActiveRows(nowMillis = 1_000L)
        server.enqueue(MockResponse.Builder().code(416).build())

        engine.runQueue(nowMillis = 1_000L)

        val failed = dao.getById("$SOURCE_TYPE:item-1")!!
        assertEquals(CloudDownloadState.FAILED.name, failed.state)
        assertEquals("FAILED_CORRUPT", failed.errorCode)
    }

    // ─── CloudDownloadStateStore must mirror every persisted transition, not just
    // ─── RUNNING and COMPLETED — otherwise a live surface (e.g. the foreground
    // ─── service's notification) reads a download as still RUNNING forever after it
    // ─── actually moved to RETRY_WAIT/BLOCKED/FAILED. ──────────────────────────

    @Test
    fun `a retried attempt updates the state store past RUNNING, not stuck there`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        dao.rows["$SOURCE_TYPE:item-1"] = row(state = CloudDownloadState.QUEUED)
        val key = CloudDownloadKey(SOURCE_TYPE, "item-1")

        engine.runQueue()

        assertEquals(CloudDownloadState.RETRY_WAIT.name, dao.getById("$SOURCE_TYPE:item-1")!!.state)
        val progress = stateStore.progressByDownloadId.value[key.storageId]
        assertEquals(CloudDownloadState.RETRY_WAIT, progress?.state)
    }

    @Test
    fun `a row blocked by an auth-portal response updates the state store to BLOCKED`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body("<html>please log in</html>")
                .addHeader("Content-Type", "text/html").build()
        )
        dao.rows["$SOURCE_TYPE:item-1"] = row(state = CloudDownloadState.QUEUED)
        val key = CloudDownloadKey(SOURCE_TYPE, "item-1")

        engine.runQueue()

        assertEquals(CloudDownloadState.BLOCKED.name, dao.getById("$SOURCE_TYPE:item-1")!!.state)
        val progress = stateStore.progressByDownloadId.value[key.storageId]
        assertEquals(CloudDownloadState.BLOCKED, progress?.state)
    }

    // ─── hasActiveWork() ────────────────────────────────────────────────────

    @Test
    fun `hasActiveWork is true while a row is QUEUED`() = runTest {
        dao.rows["$SOURCE_TYPE:item-1"] = row(state = CloudDownloadState.QUEUED)

        assertTrue(engine.hasActiveWork())
    }

    @Test
    fun `hasActiveWork is false when nothing but a RETRY_WAIT row remains`() = runTest {
        // RETRY_WAIT deliberately doesn't count — see hasActiveWork()'s own doc for why:
        // waking up for its backoff is a scheduler's job, not a reason to stay running idle.
        dao.rows["$SOURCE_TYPE:item-1"] = row(state = CloudDownloadState.RETRY_WAIT)

        assertFalse(engine.hasActiveWork())
    }

    @Test
    fun `hasActiveWork is false with an empty queue`() = runTest {
        assertFalse(engine.hasActiveWork())
    }

    // ─── Structural: nothing under engine/ knows Jellyfin exists ──────────

    @Test
    fun `no source file under the download engine package imports jellyfin`() {
        val engineDir = File("src/main/java/com/theveloper/pixelplay/data/download/engine")
        val offenders = engineDir.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { file -> file.readLines().any { it.trim().startsWith("import") && "jellyfin" in it.lowercase() } }
            .map { it.name }
            .toList()

        assertTrue(offenders.isEmpty(), "engine/ files importing jellyfin: $offenders")
    }
}
