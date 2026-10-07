package app.ownplay.mobile.feature.library.ui

import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadUserAction
import app.ownplay.mobile.feature.library.domain.LibraryContentKind
import app.ownplay.mobile.feature.library.domain.LibraryContinueWatchingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDownloadPresentationTest {
    @Test
    fun completedOfflineActionUsesResumeLabelOnlyWhenResumeIsAvailable() {
        assertEquals(
            "Play Offline",
            downloadActionLabel(DownloadUserAction.PLAY_OFFLINE, offlineResumeAvailable = false),
        )
        assertEquals(
            "Resume Offline",
            downloadActionLabel(DownloadUserAction.PLAY_OFFLINE, offlineResumeAvailable = true),
        )
    }

    @Test
    fun resumeProgressMatchesMediaKindAndContentId() {
        val movieProgress = LibraryContinueWatchingItem(
            contentKind = LibraryContentKind.MOVIE,
            contentId = "movie-1",
            title = "Movie",
            posterUrl = null,
            positionMs = 10_000L,
            durationMs = 120_000L,
            updatedAt = 1L,
        )

        assertTrue(
            hasOfflineResumeProgress(listOf(movieProgress), DownloadMediaKind.MOVIE, "movie-1"),
        )
        assertFalse(
            hasOfflineResumeProgress(listOf(movieProgress), DownloadMediaKind.EPISODE, "movie-1"),
        )
        assertFalse(
            hasOfflineResumeProgress(listOf(movieProgress), DownloadMediaKind.MOVIE, "movie-2"),
        )
    }
}
