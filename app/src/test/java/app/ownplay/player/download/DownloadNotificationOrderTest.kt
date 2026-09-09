package app.ownplay.player.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotificationOrderTest {
    @Test
    fun lexicalOrderMatchesNewestCreatedFirstAcrossDigitBoundaries() {
        val times = listOf(0L, 9L, 10L, 999L, 1000L, 1_800_000_000_000L, Long.MAX_VALUE)
        assertEquals(times.reversed(), times.sortedBy { DownloadNotificationOrder.sortKey(it, "id") })
    }

    @Test
    fun equalCreationTimeUsesStableDownloadIdentity() {
        val ids = listOf("z", "a", "b")
        assertEquals(listOf("a", "b", "z"), ids.sortedBy { DownloadNotificationOrder.sortKey(123L, it) })
    }

    @Test
    fun legacyInvalidTimeCannotOverflowTheSortKey() {
        assertEquals(0L, DownloadNotificationOrder.eventTime(Long.MIN_VALUE))
        assertEquals(DownloadNotificationOrder.sortKey(0L, "id"), DownloadNotificationOrder.sortKey(Long.MIN_VALUE, "id"))
        assertTrue(DownloadNotificationOrder.sortKey(Long.MAX_VALUE, "id") < DownloadNotificationOrder.sortKey(0L, "id"))
    }
}
