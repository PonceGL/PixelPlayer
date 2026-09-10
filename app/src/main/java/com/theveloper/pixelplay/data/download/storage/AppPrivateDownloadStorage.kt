package com.theveloper.pixelplay.data.download.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.theveloper.pixelplay.data.download.model.DownloadFileKey
import com.theveloper.pixelplay.data.download.model.DownloadStorageBackendId
import com.theveloper.pixelplay.data.download.model.StoredRef
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val DOWNLOADS_FOLDER_NAME = "PixelPlay"
private const val STAGING_SUFFIX = ".part"

/**
 * Thrown by [AppPrivateDownloadStorage.ensureReady] when the volume backing a root isn't
 * mounted — a removed SD card, not a bug. Callers are expected to catch this via the
 * [Result] `ensureReady` returns, never let it propagate.
 */
class VolumeUnavailableException(root: String) : IOException("Volume not mounted: $root")

/**
 * The default backend: files under this app's own external-files directory, invisible to
 * other apps and removed on uninstall. No permission to lose, no revocation to watch for —
 * the tradeoffs a user-chosen SAF folder, if one is ever added, would trade away.
 *
 * Layout: `<root>/PixelPlay/<sourceType>/<sanitized remoteId>.<ext>` — named by id, never by
 * title, so a metadata change (a corrected artist name, say) never renames a file a
 * [StoredRef] already points at. The staging file lives in the exact same directory as its
 * published counterpart, so [publish]'s `renameTo` is always same-volume by construction.
 */
@Singleton
class AppPrivateDownloadStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) : DownloadStorageBackend {

    override val id: DownloadStorageBackendId = DownloadStorageBackendId.APP_PRIVATE

    /**
     * The internal app-private music directory: index 0 of `getExternalFilesDirs`, which is
     * always the primary/internal volume, never a removable card. `null`
     * only when even that is missing — unlike an absent SD card, that means this device has
     * no usable external storage at all right now.
     */
    fun defaultRoot(): String? = externalMusicDirs().firstOrNull()?.absolutePath

    override suspend fun ensureReady(root: String): Result<Unit> {
        val rootDir = File(root)
        if (Environment.getExternalStorageState(rootDir) != Environment.MEDIA_MOUNTED) {
            return Result.failure(VolumeUnavailableException(root))
        }
        return if (rootDir.exists() || rootDir.mkdirs()) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("Could not create directory: $root"))
        }
    }

    override suspend fun freeBytes(root: String): Long? {
        val usableSpace = File(root).usableSpace
        // File.usableSpace returns 0 for a path that doesn't exist or isn't readable — not a
        // real "zero bytes free" measurement, so it's reported as unknown rather than as a
        // preflight-failing zero.
        return usableSpace.takeIf { it > 0 }
    }

    override suspend fun createStaging(root: String, key: DownloadFileKey): Result<File> {
        val itemDir = itemDirectory(root, key)
        if (!itemDir.exists() && !itemDir.mkdirs()) {
            return Result.failure(IOException("Could not create directory: $itemDir"))
        }
        return Result.success(File(itemDir, stagingFileName(key)))
    }

    override suspend fun publish(
        staging: File,
        root: String,
        key: DownloadFileKey,
    ): Result<StoredRef> {
        val destination = File(itemDirectory(root, key), publishedFileName(key))
        if (!staging.renameTo(destination)) {
            return Result.failure(
                IOException("renameTo failed: ${staging.absolutePath} -> ${destination.absolutePath}")
            )
        }
        return Result.success(StoredRef(backendId = id, value = destination.absolutePath))
    }

    override suspend fun open(ref: StoredRef): Uri? {
        val file = File(ref.value)
        return if (file.exists()) Uri.fromFile(file) else null
    }

    override suspend fun exists(ref: StoredRef): Boolean = File(ref.value).exists()

    override suspend fun sizeOf(ref: StoredRef): Long? {
        val file = File(ref.value)
        return if (file.exists()) file.length() else null
    }

    override suspend fun delete(ref: StoredRef) {
        // A file that's already gone is success, not an error — "no file" is the resting state
        // a delete is trying to reach, not a failure to report.
        File(ref.value).delete()
    }

    override suspend fun listOrphans(root: String, knownRefs: Set<String>): List<StoredRef> {
        val downloadsDir = File(root, DOWNLOADS_FOLDER_NAME)
        if (!downloadsDir.exists()) return emptyList()
        return downloadsDir.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.absolutePath in knownRefs }
            .map { StoredRef(backendId = id, value = it.absolutePath) }
            .toList()
    }

    private fun itemDirectory(root: String, key: DownloadFileKey): File =
        File(root, "$DOWNLOADS_FOLDER_NAME/${key.cloudDownloadKey.sourceType}")

    private fun stagingFileName(key: DownloadFileKey): String =
        sanitizeRemoteIdForFilename(key.cloudDownloadKey.remoteId) + STAGING_SUFFIX

    private fun publishedFileName(key: DownloadFileKey): String =
        "${sanitizeRemoteIdForFilename(key.cloudDownloadKey.remoteId)}.${key.extension}"

    private fun externalMusicDirs(): List<File> =
        context.getExternalFilesDirs(Environment.DIRECTORY_MUSIC).filterNotNull()
}
