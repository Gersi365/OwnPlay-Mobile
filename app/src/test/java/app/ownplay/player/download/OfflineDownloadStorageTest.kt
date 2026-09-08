package app.ownplay.player.download

import android.os.Environment
import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.persistence.download.MediaDownloadEntity
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloadStorageTest {
    @Test
    fun mediaStoreUriIsRecognizedAsPhoneDownloadsLocation() {
        assertTrue(
            OfflineDownloadStorage.isPublicDownloadsLocation(
                "content://media/external/downloads/42",
            ),
        )
        assertFalse(
            OfflineDownloadStorage.isPublicDownloadsLocation(
                "content://com.example.documents/document/42",
            ),
        )
        assertFalse(OfflineDownloadStorage.isPublicDownloadsLocation("offline/movie.mp4"))
        assertFalse(OfflineDownloadStorage.isPublicDownloadsLocation(null))
    }

    @Test
    fun publicFilenameStemRemovesFilesystemSeparatorsAndReservedCharacters() {
        assertEquals(
            "Movie Name Final",
            OfflineDownloadStorage.safeFileStem("Movie/Name:*?\"<>| Final"),
        )
    }

    @Test
    fun publicFilenameStemRemovesControlCharacters() {
        assertEquals(
            "Movie Name Final",
            OfflineDownloadStorage.safeFileStem("Movie\u0000Name\u0007 Final"),
        )
    }

    @Test
    fun moviePublicPathUsesCanonicalOwnPlayHierarchy() {
        assertEquals(
            "${Environment.DIRECTORY_DOWNLOADS}/OwnPlay Downloads/Movies",
            OfflineDownloadStorage.publicRelativePath(
                downloadRow(mediaKind = DownloadMediaKinds.MOVIE),
            ),
        )
    }

    @Test
    fun seriesPublicPathUsesSafeSeriesAndPaddedSeasonHierarchy() {
        assertEquals(
            "${Environment.DIRECTORY_DOWNLOADS}/OwnPlay Downloads/Series/My Series/Season 02",
            OfflineDownloadStorage.publicRelativePath(
                downloadRow(
                    mediaKind = DownloadMediaKinds.SERIES_EPISODE,
                    seriesTitle = "My/Series:*?",
                    seasonNumber = 2,
                ),
            ),
        )
    }

    @Test
    fun seriesPublicPathHasDeterministicFallbackForMissingMetadata() {
        assertEquals(
            "${Environment.DIRECTORY_DOWNLOADS}/OwnPlay Downloads/Series/Unknown Series/Season 00",
            OfflineDownloadStorage.publicRelativePath(
                downloadRow(mediaKind = DownloadMediaKinds.SERIES_EPISODE),
            ),
        )
    }

    @Test
    fun extensionNormalizationDoesNotDependOnDeviceLocale() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))

            assertEquals("avi", OfflineDownloadStorage.normalizeExtension(" AVI "))
            assertEquals("mp4", OfflineDownloadStorage.normalizeExtension("bad.extension"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun pendingDownloadMarkerIsDeterministicForDownloadId() {
        assertEquals(
            "ownplay://offline-download/123e4567-e89b-12d3-a456-426614174000",
            OfflineDownloadStorage.pendingDownloadMarker(
                "123e4567-e89b-12d3-a456-426614174000",
            ),
        )
    }

    @Test
    fun pendingDestinationRequiresExactOwnPackageAndDownloadMarker() {
        val downloadId = "123e4567-e89b-12d3-a456-426614174000"
        val marker = OfflineDownloadStorage.pendingDownloadMarker(downloadId)

        assertTrue(
            OfflineDownloadStorage.isOwnedPendingDownloadCandidate(
                ownerPackageName = "app.ownplay.mobile",
                expectedPackageName = "app.ownplay.mobile",
                downloadUri = marker,
                expectedDownloadId = downloadId,
            ),
        )
        assertFalse(
            OfflineDownloadStorage.isOwnedPendingDownloadCandidate(
                ownerPackageName = null,
                expectedPackageName = "app.ownplay.mobile",
                downloadUri = marker,
                expectedDownloadId = downloadId,
            ),
        )
        assertFalse(
            OfflineDownloadStorage.isOwnedPendingDownloadCandidate(
                ownerPackageName = "com.example.other",
                expectedPackageName = "app.ownplay.mobile",
                downloadUri = marker,
                expectedDownloadId = downloadId,
            ),
        )
        assertFalse(
            OfflineDownloadStorage.isOwnedPendingDownloadCandidate(
                ownerPackageName = "app.ownplay.mobile",
                expectedPackageName = "app.ownplay.mobile",
                downloadUri = "ownplay://offline-download/other",
                expectedDownloadId = downloadId,
            ),
        )
    }

    private fun downloadRow(
        mediaKind: String,
        seriesTitle: String? = null,
        seasonNumber: Int? = null,
    ): MediaDownloadEntity = MediaDownloadEntity(
        downloadId = "download-id",
        sourceId = "source-id",
        mediaKind = mediaKind,
        contentId = "content-id",
        providerStreamId = 1,
        title = "Episode Title",
        seriesTitle = seriesTitle,
        seasonNumber = seasonNumber,
        episodeNumber = 1,
        posterUrl = null,
        containerExtension = "mp4",
        state = "QUEUED",
        bytesDownloaded = 0L,
        totalBytes = null,
        localRelativePath = null,
        failureReason = null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )
}
