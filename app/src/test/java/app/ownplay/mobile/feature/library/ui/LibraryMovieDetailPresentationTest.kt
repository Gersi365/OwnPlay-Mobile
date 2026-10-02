package app.ownplay.mobile.feature.library.ui

import app.ownplay.mobile.feature.library.domain.LibraryMovieDetailMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryMovieDetailPresentationTest {
    @Test
    fun runtimeLabelUsesHoursAndMinutesDeterministically() {
        assertEquals("2h 5m", LibraryMovieDetailPresentation.runtimeLabel(7_500_000L))
        assertEquals("2h", LibraryMovieDetailPresentation.runtimeLabel(7_200_000L))
        assertEquals("45m", LibraryMovieDetailPresentation.runtimeLabel(2_700_000L))
        assertNull(LibraryMovieDetailPresentation.runtimeLabel(null))
        assertNull(LibraryMovieDetailPresentation.runtimeLabel(30_000L))
    }

    @Test
    fun metadataLineUsesOnlyAvailableFields() {
        val metadata = LibraryMovieDetailMetadata(
            posterUrl = null,
            backdropUrl = null,
            plot = null,
            releaseDate = "2024-01-01",
            year = "2024",
            runtimeMs = 7_500_000L,
            rating = "8.4",
        )

        assertEquals(
            "2024 • 2h 5m • ★ 8.4",
            LibraryMovieDetailPresentation.metadataLine(metadata),
        )
    }
}
