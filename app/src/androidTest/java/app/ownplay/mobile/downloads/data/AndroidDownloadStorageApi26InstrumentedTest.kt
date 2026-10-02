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
