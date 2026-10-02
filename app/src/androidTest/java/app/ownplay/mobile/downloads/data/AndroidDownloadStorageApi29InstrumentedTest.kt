package app.ownplay.mobile.downloads.data

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.ownplay.mobile.downloads.domain.DownloadDestinationPolicy
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadPendingNamePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29, maxSdkVersion = 29)
class AndroidDownloadStorageApi29InstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver
    private val storage = AndroidDownloadStorage(
        context,
        Api29DestinationAssignmentStore(DownloadDestinationPolicy.DEFAULT_DESTINATION),
    )

    @Test
    fun mediaStorePendingPublishesWithExpectedPathAndVisibility() = runBlocking {
        assertEquals(29, Build.VERSION.SDK_INT)
        val downloadId = DownloadId("download:api29-storage-publish")
        storage.discardPending(downloadId)
        val media = ResolvedDownloadMedia(
            uri = "https://qa.invalid/movie.mp4",
            extension = "mp4",
            displayName = "API29 QA Movie.mp4",
            relativeDirectories = listOf("Movies"),
        )
        val pending = requireNotNull(storage.openPending(downloadId, media))
        val token = pending.token as PendingDownloadToken.MediaStoreEntry
        val payload = "ownplay-api29-storage-publish".toByteArray()
        pending.outputStream.use { it.write(payload) }

        assertEquals(payload.size.toLong(), storage.verifiedSize(pending))
        val before = readRow(token)
        assertTrue(before.displayName.startsWith(DownloadPendingNamePolicy.stagingDisplayName(downloadId)))
        assertEquals("Download/OwnPlay Downloads/Movies/", before.relativePath)
        assertEquals(1, before.isPending)

        val localReference = requireNotNull(storage.publish(pending))
        assertEquals(token.uri.toString(), localReference)
        val after = readRow(token)
        assertEquals(media.displayName, after.displayName)
        assertEquals("Download/OwnPlay Downloads/Movies/", after.relativePath)
        assertEquals(0, after.isPending)

        assertTrue(storage.removePublished(localReference))
        assertFalse(rowExists(token))
    }

    @Test
    fun discardPendingAfterPublishPreservesCompletedMediaStoreEntry() = runBlocking {
        assertEquals(29, Build.VERSION.SDK_INT)
        val downloadId = DownloadId("download:api29-source-removal-published")
        storage.discardPending(downloadId)
        val media = ResolvedDownloadMedia(
            uri = "https://qa.invalid/published.mp4",
            extension = "mp4",
            displayName = "API29 QA Published.mp4",
            relativeDirectories = listOf("Movies"),
        )
        val pending = requireNotNull(storage.openPending(downloadId, media))
        val token = pending.token as PendingDownloadToken.MediaStoreEntry
        pending.outputStream.use { it.write("published".toByteArray()) }

        val localReference = requireNotNull(storage.publish(pending))
        val published = readRow(token)
        assertEquals(media.displayName, published.displayName)
        assertEquals(0, published.isPending)

        assertTrue(storage.discardPending(downloadId))

        assertTrue(rowExists(token))
        val afterCleanup = readRow(token)
        assertEquals(media.displayName, afterCleanup.displayName)
        assertEquals(0, afterCleanup.isPending)

        assertTrue(storage.removePublished(localReference))
        assertFalse(rowExists(token))
    }

    @Test
    fun discardPendingRemovesOrphanedMediaStoreEntry() = runBlocking {
        assertEquals(29, Build.VERSION.SDK_INT)
        val downloadId = DownloadId("download:api29-storage-discard")
        storage.discardPending(downloadId)
        val media = ResolvedDownloadMedia(
            uri = "https://qa.invalid/orphan.mp4",
            extension = "mp4",
            displayName = "API29 QA Orphan.mp4",
            relativeDirectories = listOf("Movies"),
        )
        val pending = requireNotNull(storage.openPending(downloadId, media))
        val token = pending.token as PendingDownloadToken.MediaStoreEntry
        pending.outputStream.use { it.write("orphan".toByteArray()) }

        assertTrue(rowExists(token))
        assertTrue(storage.discardPending(downloadId))
        assertFalse(rowExists(token))
    }

    private fun readRow(token: PendingDownloadToken.MediaStoreEntry): MediaRow {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.SIZE,
        )
        resolver.query(token.uri, projection, null, null, null).use { cursor ->
            assertNotNull(cursor)
            requireNotNull(cursor)
            assertTrue(cursor.moveToFirst())
            return MediaRow(
                displayName = cursor.getString(0),
                relativePath = cursor.getString(1),
                isPending = cursor.getInt(2),
                size = cursor.getLong(3),
            )
        }
    }

    private fun rowExists(token: PendingDownloadToken.MediaStoreEntry): Boolean =
        resolver.query(
            token.uri,
            arrayOf(MediaStore.MediaColumns._ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } == true

    private data class MediaRow(
        val displayName: String,
        val relativePath: String,
        val isPending: Int,
        val size: Long,
    )
}

private class Api29DestinationAssignmentStore(
    private val destination: String,
) : DownloadDestinationAssignmentStore {
    override suspend fun get(downloadId: DownloadId): String = destination

    override suspend fun pin(
        downloadId: DownloadId,
        destinationRelativePath: String,
    ): Boolean = true

    override suspend fun remove(downloadId: DownloadId): Boolean = true
}
