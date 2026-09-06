package app.ownplay.player.download

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineDownloadStatusTest {
    @Test
    fun normalQueueHasSimpleLabel() {
        assertEquals("Queued", queuedDownloadStatusLabel(null))
        assertEquals("Queued", queuedDownloadStatusLabel("   "))
    }

    @Test
    fun automaticRetryExplainsWhyItIsQueued() {
        assertEquals(
            "Retry scheduled · Provider returned HTTP 503",
            queuedDownloadStatusLabel(" Provider returned HTTP 503 "),
        )
    }
}
