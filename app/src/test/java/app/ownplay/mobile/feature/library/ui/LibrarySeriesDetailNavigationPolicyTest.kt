package app.ownplay.mobile.feature.library.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibrarySeriesDetailNavigationPolicyTest {
    @Test
    fun detailIsTheSeriesNavigationRoot() {
        assertNull(
            LibrarySeriesDetailNavigationPolicy.parent(
                LibrarySeriesDetailPage.DETAIL,
            ),
        )
    }

    @Test
    fun seasonAndEpisodePagesBacktrackHierarchically() {
        assertEquals(
            LibrarySeriesDetailPage.DETAIL,
            LibrarySeriesDetailNavigationPolicy.parent(
                LibrarySeriesDetailPage.SEASONS,
            ),
        )
        assertEquals(
            LibrarySeriesDetailPage.SEASONS,
            LibrarySeriesDetailNavigationPolicy.parent(
                LibrarySeriesDetailPage.EPISODES,
            ),
        )
        assertEquals(
            LibrarySeriesDetailPage.EPISODES,
            LibrarySeriesDetailNavigationPolicy.parent(
                LibrarySeriesDetailPage.EPISODE_DETAIL,
            ),
        )
    }
}
