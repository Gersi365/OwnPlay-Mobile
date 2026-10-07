package app.ownplay.mobile.downloads.data

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadDestinationPolicy
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26, maxSdkVersion = 26)
class AndroidDownloadStorageApi26InstrumentedTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var storage: AndroidDownloadStorage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "ownplay-downloads")
        root.deleteRecursively()
        storage = AndroidDownloadStorage(
            context,
            FixedDestinationAssignmentStore(DownloadDestinationPolicy.DEFAULT_DESTINATION),
        )
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        File(context.filesDir, "api26-outside-private-qa.bin").delete()
    }

    @Test
    fun privatePendingPublishesVerifiesBytesAndRemovesSafely() = runBlocking {
        assertEquals(26, Build.VERSION.SDK_INT)
        val downloadId = DownloadId("download:api26-private-publish")
        val media = ResolvedDownloadMedia(
            uri = "https://qa.invalid/movie.mp4",
            extension = "mp4",
            displayName = "API26 QA Movie.mp4",
            relativeDirectories = listOf("Movies"),
        )
        val pending = requireNotNull(storage.openPending(downloadId, media))
        val token = pending.token as PendingDownloadToken.PrivateFile
        val payload = "ownplay-api26-private-storage".toByteArray()
        pending.outputStream.use { it.write(payload) }

        assertEquals(payload.size.toLong(), storage.verifiedSize(pending))
        assertTrue(token.temporaryFile.isFile)
        assertFalse(token.finalFile.exists())
        assertTrue(token.temporaryFile.path.contains("ownplay-downloads/pending/"))
        assertTrue(
            token.finalFile.path.endsWith(
                "ownplay-downloads/completed/OwnPlay Downloads/Movies/API26 QA Movie.mp4",
            ),
        )

        val localReference = requireNotNull(storage.publish(pending))
        assertEquals(Uri.fromFile(token.finalFile).toString(), localReference)
        assertFalse(token.temporaryFile.exists())
        assertTrue(token.finalFile.isFile)
        assertArrayEquals(payload, token.finalFile.readBytes())

        assertTrue(storage.removePublished(localReference))
        assertFalse(token.finalFile.exists())
    }

    @Test
    fun failedPrivateReplacementRestoresGoodOutputAndSuccessfulCommitCleansBackup() = runBlocking {
        assertEquals(26, Build.VERSION.SDK_INT)
        val id = DownloadId("download:api26-replacement-rollback")
        val media = ResolvedDownloadMedia(
            uri = "https://qa.invalid/replacement.mp4",
            extension = "mp4",
            displayName = "API26 Replacement.mp4",
            relativeDirectories = listOf("Movies"),
        )
        val initial = requireNotNull(storage.openPending(id, media))
        val token = initial.token as PendingDownloadToken.PrivateFile
        initial.outputStream.use { it.write("known-good".toByteArray()) }
        requireNotNull(storage.publish(initial))
        assertTrue(token.finalFile.isFile)

        var moves = 0
        val failingStorage = AndroidDownloadStorage(
            context,
            FixedDestinationAssignmentStore(DownloadDestinationPolicy.DEFAULT_DESTINATION),
        ) { source, target, replace ->
            moves += 1
            if (moves == 2) false
            else {
                val options = if (replace) {
                    arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    arrayOf(StandardCopyOption.ATOMIC_MOVE)
                }
                runCatching { Files.move(source.toPath(), target.toPath(), *options); true }.getOrDefault(false)
            }
        }
        val failedReplacement = requireNotNull(failingStorage.openPending(id, media))
        val failedToken = failedReplacement.token as PendingDownloadToken.PrivateFile
        failedReplacement.outputStream.use { it.write("replacement-bytes".toByteArray()) }

        assertEquals(null, failingStorage.publish(failedReplacement))
        assertArrayEquals("known-good".toByteArray(), failedToken.finalFile.readBytes())
        assertFalse(requireNotNull(failedToken.backupFile).exists())
        failingStorage.discard(failedReplacement)
        assertArrayEquals("known-good".toByteArray(), failedToken.finalFile.readBytes())

        val successfulReplacement = requireNotNull(storage.openPending(id, media))
        val successfulToken = successfulReplacement.token as PendingDownloadToken.PrivateFile
        successfulReplacement.outputStream.use { it.write("committed-replacement".toByteArray()) }
        val replacementReference = requireNotNull(storage.publish(successfulReplacement))
        assertTrue(requireNotNull(successfulToken.backupFile).isFile)
        assertTrue(storage.finalizePublished(successfulReplacement, replacementReference))
        assertFalse(requireNotNull(successfulToken.backupFile).exists())
        assertArrayEquals("committed-replacement".toByteArray(), successfulToken.finalFile.readBytes())
        assertEquals(1, File(root, "completed").walkTopDown().count { it.isFile })
    }

    @Test
    fun privateReplacementRecoversAfterOldFileWasMovedToBackup() = runBlocking {
        assertEquals(26, Build.VERSION.SDK_INT)
        val id = DownloadId("download:api26-replacement-recovery")
        val media = ResolvedDownloadMedia(
            uri = "https://qa.invalid/recovery.mp4",
            extension = "mp4",
            displayName = "API26 Replacement Recovery.mp4",
            relativeDirectories = listOf("Movies"),
        )
        val old = requireNotNull(storage.openPending(id, media))
        val oldToken = old.token as PendingDownloadToken.PrivateFile
        old.outputStream.use { it.write("old-last-good".toByteArray()) }
        requireNotNull(storage.publish(old))

        val newPending = requireNotNull(storage.openPending(id, media))
        val token = newPending.token as PendingDownloadToken.PrivateFile
        newPending.outputStream.use { it.write("verified-new-output".toByteArray()) }
        val descriptor = storage.describePending(newPending)
        val backup = requireNotNull(token.backupFile)
        assertTrue(Files.move(oldToken.finalFile.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE).toFile().isFile)

        val recoveredPending = requireNotNull(storage.recoverOwnedPending(id, descriptor))
        assertEquals("verified-new-output", storage.openPendingInput(recoveredPending)!!.bufferedReader().use { it.readText() })
        val reference = requireNotNull(storage.publish(recoveredPending))
        val published = requireNotNull(storage.recoverOwnedPublished(id, descriptor))
        assertEquals(reference, published.localReference)
        assertEquals("verified-new-output", published.inputStream.bufferedReader().use { it.readText() })
        assertArrayEquals("verified-new-output".toByteArray(), token.finalFile.readBytes())
        assertTrue(storage.finalizePublished(recoveredPending, reference))
        assertFalse(backup.exists())
    }

    @Test
    fun missingPendingFileRecoveryRestoresBackupAndCanClearMarker() = runBlocking {
        assertEquals(26, Build.VERSION.SDK_INT)
        val id = DownloadId("download:api26-missing-stage-recovery")
        val media = ResolvedDownloadMedia(
            uri = "https://qa.invalid/missing-stage.mp4",
            extension = "mp4",
            displayName = "API26 Missing Stage.mp4",
            relativeDirectories = listOf("Movies"),
        )
        val old = requireNotNull(storage.openPending(id, media))
        val oldToken = old.token as PendingDownloadToken.PrivateFile
        old.outputStream.use { it.write("last-good-output".toByteArray()) }
        requireNotNull(storage.publish(old))

        val replacement = requireNotNull(storage.openPending(id, media))
        val replacementToken = replacement.token as PendingDownloadToken.PrivateFile
        replacement.outputStream.use { it.write("verified-but-removed".toByteArray()) }
        val descriptor = storage.describePending(replacement)
        val backup = requireNotNull(replacementToken.backupFile)
        Files.move(oldToken.finalFile.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
        assertTrue(replacementToken.temporaryFile.delete())

        val recovered = requireNotNull(storage.recoverOwnedPending(id, descriptor))
        assertTrue(storage.pendingOutputMissing(recovered))
        storage.discard(recovered)

        assertArrayEquals("last-good-output".toByteArray(), replacementToken.finalFile.readBytes())
        assertFalse(backup.exists())
    }

    @Test
    fun discardPendingAfterPublishPreservesCompletedPrivateFile() = runBlocking {
        assertEquals(26, Build.VERSION.SDK_INT)
        val downloadId = DownloadId("download:api26-source-removal-published")
        val media = ResolvedDownloadMedia(
            uri = "https://qa.invalid/published.mp4",
            extension = "mp4",
            displayName = "API26 QA Published.mp4",
            relativeDirectories = listOf("Movies"),
        )
        val pending = requireNotNull(storage.openPending(downloadId, media))
        val token = pending.token as PendingDownloadToken.PrivateFile
        val payload = "ownplay-api26-published-preserved".toByteArray()
        pending.outputStream.use { it.write(payload) }

        val localReference = requireNotNull(storage.publish(pending))
        assertTrue(token.finalFile.isFile)
        assertFalse(token.temporaryFile.exists())

        assertTrue(storage.discardPending(downloadId))

        assertTrue(token.finalFile.isFile)
        assertArrayEquals(payload, token.finalFile.readBytes())

        assertTrue(storage.removePublished(localReference))
        assertFalse(token.finalFile.exists())
    }

    @Test
    fun discardPendingRemovesPrivatePartFile() = runBlocking {
        assertEquals(26, Build.VERSION.SDK_INT)
        val downloadId = DownloadId("download:api26-private-discard")
        val media = ResolvedDownloadMedia(
            uri = "https://qa.invalid/orphan.mp4",
            extension = "mp4",
            displayName = "API26 QA Orphan.mp4",
            relativeDirectories = listOf("Movies"),
        )
        val pending = requireNotNull(storage.openPending(downloadId, media))
        val token = pending.token as PendingDownloadToken.PrivateFile
        pending.outputStream.use { it.write("orphan".toByteArray()) }

        assertTrue(token.temporaryFile.isFile)
        assertTrue(storage.discardPending(downloadId))
        assertFalse(token.temporaryFile.exists())
        assertFalse(token.finalFile.exists())
    }

    @Test
    fun removePublishedRejectsFileOutsideCompletedRoot() = runBlocking {
        assertEquals(26, Build.VERSION.SDK_INT)
        val outside = File(context.filesDir, "api26-outside-private-qa.bin")
        outside.writeText("must-remain")

        assertFalse(storage.removePublished(Uri.fromFile(outside).toString()))
        assertTrue(outside.isFile)
        assertEquals("must-remain", outside.readText())
    }
}

private class FixedDestinationAssignmentStore(
    private val destination: String,
) : DownloadDestinationAssignmentStore {
    override suspend fun get(downloadId: DownloadId): String = destination

    override suspend fun pin(
        downloadId: DownloadId,
        destinationRelativePath: String,
    ): Boolean = true

    override suspend fun remove(downloadId: DownloadId): Boolean = true
}
