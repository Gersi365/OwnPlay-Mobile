package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.sources.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIdentityTest {
    @Test
    fun sameLogicalMediaProducesSameDownloadId() {
        val first = DownloadIdentity.stableId(SourceId("source-1"), DownloadMediaKind.MOVIE, "movie-7")
        val second = DownloadIdentity.stableId(SourceId("source-1"), DownloadMediaKind.MOVIE, "movie-7")
        assertEquals(first, second)
        assertTrue(first.value.startsWith("download:"))
    }

    @Test
    fun identityChangesAcrossSourceKindOrContent() {
        val base = DownloadIdentity.stableId(SourceId("source-1"), DownloadMediaKind.MOVIE, "item-1")
        assertNotEquals(base, DownloadIdentity.stableId(SourceId("source-2"), DownloadMediaKind.MOVIE, "item-1"))
        assertNotEquals(base, DownloadIdentity.stableId(SourceId("source-1"), DownloadMediaKind.EPISODE, "item-1"))
        assertNotEquals(base, DownloadIdentity.stableId(SourceId("source-1"), DownloadMediaKind.MOVIE, "item-2"))
    }
}
