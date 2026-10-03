package app.ownplay.mobile.downloads.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import app.ownplay.mobile.downloads.domain.DownloadDestinationPolicy
import app.ownplay.mobile.downloads.domain.DownloadId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecordingStagingOwnershipInstrumentedTest {
    private val storage = AndroidDownloadStorage(ApplicationProvider.getApplicationContext<Context>())

    @Test @SdkSuppress(minSdkVersion = 29)
    fun onlyExactOwnPendingRecordingCanBeRecoveredAndPublishedIsExcluded() = runBlocking {
        val id = DownloadId("recording-ownership-${System.nanoTime()}")
        val pending = requireNotNull(storage.openPendingAtDestination(
            id, ResolvedDownloadMedia("https://fixture.invalid/live.ts", "ts", "QA-recovery-${System.nanoTime()}.ts", listOf("Recordings")),
            DownloadDestinationPolicy.DEFAULT_DESTINATION,
        ))
        var published: String? = null
        try {
            val payload = ByteArray(188 * 64).apply { for (index in 0 until 64) this[index * 188] = 0x47 }
            pending.outputStream.use { it.write(payload) }
            val descriptor = storage.describePending(pending)
            assertNull(storage.recoverOwnedPending(DownloadId("another-recording"), descriptor))
            assertNull(storage.recoverOwnedPending(id, "{\"kind\":\"media-store\",\"reference\":\"content://foreign/downloads/1\",\"finalName\":\"file.ts\"}"))
            val recovered = requireNotNull(storage.recoverOwnedPending(id, descriptor))
            assertArrayEquals(payload, requireNotNull(storage.openPendingInput(recovered)).use { it.readBytes() })
            published = requireNotNull(storage.publish(recovered))
            assertNull(storage.recoverOwnedPending(id, descriptor))
            assertTrue(storage.discardPending(id))
            assertEquals(payload.size.toLong(), storage.verifiedSize(recovered))
        } finally {
            published?.let { storage.removePublished(it) } ?: storage.discard(pending)
        }
    }
}
