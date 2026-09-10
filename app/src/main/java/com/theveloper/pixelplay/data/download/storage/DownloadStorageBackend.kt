package com.theveloper.pixelplay.data.download.storage

import android.net.Uri
import com.theveloper.pixelplay.data.download.model.DownloadFileKey
import com.theveloper.pixelplay.data.download.model.DownloadStorageBackendId
import com.theveloper.pixelplay.data.download.model.StoredRef
import java.io.File

/**
 * Where a download's bytes actually live, abstracted away from the engine. Two
 * implementations share this contract: [AppPrivateDownloadStorage] and a SAF backend for a
 * user-chosen folder (not written yet — this interface is designed knowing it will exist).
 *
 * **The structural decision that governs both implementations: the staging file is always a
 * plain [File], never a `content://` write stream.** `RandomAccessFile.seek()` for `Range`
 * resumption works identically on both backends this way, while append-mode writes through
 * `ContentResolver` are provider-dependent and unreliable on removable storage. Recovery after
 * a crash then has **one rule for both backends**: a staging file with no `COMPLETED` row for
 * it is garbage. The cost on SAF is that [publish] there is a full copy, not a rename — a
 * transient 2× size peak that the preflight step has to budget for.
 *
 * A backend never throws for conditions its caller should expect and handle: a missing root,
 * an unmounted volume or a revoked permission are facts about storage, not programming errors.
 * [ensureReady] reports them as a failed [Result]; every other method that can legitimately
 * find nothing reports that as its return value (`false`, `null`, or an empty list) rather
 * than throwing.
 */
interface DownloadStorageBackend {

    /** Which backend this is — matches `cloud_downloads.storage_backend`. */
    val id: DownloadStorageBackendId

    /**
     * Confirms [root] is currently usable (the volume it lives on is mounted, the SAF tree
     * permission still holds, ...) and creates it if it doesn't exist yet. Never throws: an
     * unusable root is a failed [Result], not an exception — a removed SD card is a normal
     * state of the system, not a bug.
     */
    suspend fun ensureReady(root: String): Result<Unit>

    /** Bytes free on the volume or tree backing [root], or `null` if that can't be measured. */
    suspend fun freeBytes(root: String): Long?

    /**
     * Creates (or reuses) the staging [File] for [key] under [root]. Always a real file on
     * disk, on both backends — see the class doc. Never publishes anything by itself.
     *
     * A failed [Result], not an exception, when the destination directory can't be created —
     * the disk being full mid-preflight is exactly the kind of expected failure this models as
     * a return value, not a thrown exception.
     */
    suspend fun createStaging(root: String, key: DownloadFileKey): Result<File>

    /**
     * Moves the finished [staging] file into its permanent place under [root] and returns the
     * [StoredRef] that identifies it from then on. A rename within the same volume on
     * [AppPrivateDownloadStorage] (no byte copy); a full copy on a SAF backend, once one
     * exists.
     *
     * A failed [Result] when the move itself fails — e.g. `File.renameTo` returning `false`
     * because [staging] unexpectedly ended up on a different volume than expected. The caller
     * decides what that means; this method only reports it.
     */
    suspend fun publish(staging: File, root: String, key: DownloadFileKey): Result<StoredRef>

    /** A `content://` or `file://` [Uri] a player/loader can open [ref] with, or `null`. */
    suspend fun open(ref: StoredRef): Uri?

    /** Whether the file [ref] points at is actually there right now. */
    suspend fun exists(ref: StoredRef): Boolean

    /** Size in bytes of the file [ref] points at, or `null` if it can't be read. */
    suspend fun sizeOf(ref: StoredRef): Long?

    /** Removes the file [ref] points at. A no-op, not a failure, if it's already gone. */
    suspend fun delete(ref: StoredRef)

    /**
     * Files under [root] this backend manages that aren't accounted for by [knownRefs] (the
     * `value` of every [StoredRef] Room currently knows about) — leftovers from a crash mid
     * publish, or a `.part` whose row got deleted. Returns an empty list, never throws, if
     * [root] doesn't exist.
     */
    suspend fun listOrphans(root: String, knownRefs: Set<String>): List<StoredRef>
}
