package com.theveloper.pixelplay.data.download.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.theveloper.pixelplay.data.database.SourceType
import com.theveloper.pixelplay.data.download.model.CloudDownloadKey
import com.theveloper.pixelplay.data.download.model.DownloadFileKey
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [AppPrivateDownloadStorage] against real files on the connected device/emulator —
 * this is exactly the kind of test D-22 keeps out of JVM (no Robolectric): volume state,
 * `getExternalFilesDirs`, and real renames only mean anything against a real filesystem.
 *
 * `PLAN.md` §F1.5 aceptación: `createStaging` → write → `publish` → `exists` is true and
 * `sizeOf` matches; `publish` doesn't copy bytes; `listOrphans` finds an untracked file;
 * `ensureReady` on a nonexistent root fails without throwing.
 */
@RunWith(AndroidJUnit4::class)
class AppPrivateDownloadStorageTest {

    private lateinit var context: Context
    private lateinit var storage: AppPrivateDownloadStorage
    private lateinit var testRoot: String

    private val key = DownloadFileKey(
        cloudDownloadKey = CloudDownloadKey(SourceType.JELLYFIN, "test-item-${System.nanoTime()}"),
        extension = "flac",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = AppPrivateDownloadStorage(context)
        // A throwaway root under the real app-private external directory, so
        // Environment.getExternalStorageState sees a genuinely mounted volume.
        testRoot = requireNotNull(context.getExternalFilesDir(null)) {
            "Device/emulator under test has no external files dir"
        }.resolve("download-storage-test-${System.nanoTime()}").absolutePath
    }

    @After
    fun tearDown() {
        java.io.File(testRoot).deleteRecursively()
    }

    @Test
    fun defaultRoot_isNotNull_onADeviceWithExternalStorage() {
        assertNotNull(storage.defaultRoot())
    }

    @Test
    fun ensureReady_succeeds_andCreatesTheDirectory() = runTest {
        val result = storage.ensureReady(testRoot)

        assertTrue(result.isSuccess)
        assertTrue(java.io.File(testRoot).exists())
    }

    @Test
    fun ensureReady_onAnUnmountedVolume_failsWithoutThrowing() = runTest {
        // A path that cannot possibly be a mounted volume's root.
        val bogusRoot = "/definitely/not/a/real/mount/point/${System.nanoTime()}"

        val result = storage.ensureReady(bogusRoot)

        assertTrue(result.isFailure)
    }

    @Test
    fun createStaging_write_publish_exists_sizeOf_roundTrip() = runTest {
        storage.ensureReady(testRoot)

        val staging = storage.createStaging(testRoot, key).getOrThrow()
        val bytes = "PixelPlay download storage test".toByteArray()
        staging.writeBytes(bytes)

        val ref = storage.publish(staging, testRoot, key).getOrThrow()

        assertTrue(storage.exists(ref))
        assertEquals(bytes.size.toLong(), storage.sizeOf(ref))
    }

    @Test
    fun publish_renames_ratherThanCopies_theStagingFile() = runTest {
        storage.ensureReady(testRoot)

        val staging = storage.createStaging(testRoot, key).getOrThrow()
        staging.writeBytes(byteArrayOf(1, 2, 3))
        val stagingPath = staging.absolutePath

        storage.publish(staging, testRoot, key).getOrThrow()

        // A rename leaves nothing behind at the original path; a copy would leave the
        // staging file sitting there too.
        assertFalse(java.io.File(stagingPath).exists())
    }

    @Test
    fun delete_removesThePublishedFile() = runTest {
        storage.ensureReady(testRoot)
        val staging = storage.createStaging(testRoot, key).getOrThrow()
        staging.writeBytes(byteArrayOf(1))
        val ref = storage.publish(staging, testRoot, key).getOrThrow()

        storage.delete(ref)

        assertFalse(storage.exists(ref))
    }

    @Test
    fun delete_onAnAlreadyMissingFile_doesNotThrow() = runTest {
        storage.ensureReady(testRoot)
        val staging = storage.createStaging(testRoot, key).getOrThrow()
        staging.writeBytes(byteArrayOf(1))
        val ref = storage.publish(staging, testRoot, key).getOrThrow()
        storage.delete(ref)

        storage.delete(ref) // second delete of the same, now-missing, ref
    }

    @Test
    fun listOrphans_findsAFileNotInKnownRefs() = runTest {
        storage.ensureReady(testRoot)
        val staging = storage.createStaging(testRoot, key).getOrThrow()
        staging.writeBytes(byteArrayOf(1))
        val ref = storage.publish(staging, testRoot, key).getOrThrow()

        val orphans = storage.listOrphans(testRoot, knownRefs = emptySet())

        assertTrue(orphans.any { it.value == ref.value })
    }

    @Test
    fun listOrphans_excludesFilesPresentInKnownRefs() = runTest {
        storage.ensureReady(testRoot)
        val staging = storage.createStaging(testRoot, key).getOrThrow()
        staging.writeBytes(byteArrayOf(1))
        val ref = storage.publish(staging, testRoot, key).getOrThrow()

        val orphans = storage.listOrphans(testRoot, knownRefs = setOf(ref.value))

        assertTrue(orphans.none { it.value == ref.value })
    }

    @Test
    fun listOrphans_onARootThatWasNeverCreated_returnsEmptyWithoutThrowing() = runTest {
        val neverCreatedRoot = "$testRoot-never-created"

        val orphans = storage.listOrphans(neverCreatedRoot, knownRefs = emptySet())

        assertTrue(orphans.isEmpty())
    }
}
