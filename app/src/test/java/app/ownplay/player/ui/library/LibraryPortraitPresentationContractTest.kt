package app.ownplay.player.ui.library

import app.ownplay.player.testing.normalizedSource
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPortraitPresentationContractTest {
    @Test
    fun `Continue Watching precedes Library section controls`() {
        val route = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt"),
        )
        val continueWatchingIndex = route.indexOf("LibraryUnifiedContinueWatchingStrip(")
        val sectionControlsIndex = route.indexOf("items = UnifiedLibraryFilter.entries")

        assertTrue(continueWatchingIndex >= 0)
        assertTrue(sectionControlsIndex > continueWatchingIndex)
        assertFalse(route.contains("LibraryMovieContinueWatchingStrip("))
        assertFalse(route.contains("LibrarySeriesContinueWatchingStrip("))
    }

    @Test
    fun `Library section order is Movies Series Downloads before Search`() {
        val route = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt"),
        )
        val sectionControlsIndex = route.indexOf("items = UnifiedLibraryFilter.entries")
        val searchIndex = route.indexOf("item(key = \"library-search\")")

        assertTrue(sectionControlsIndex >= 0)
        assertTrue(searchIndex > sectionControlsIndex)
        assertTrue(route.contains("UnifiedLibraryFilter.OFFLINE -> \"Downloads\""))
        assertTrue(route.contains("UnifiedLibraryFilter.MOVIES -> \"Movies\""))
        assertTrue(route.contains("UnifiedLibraryFilter.SERIES -> \"Series\""))
        assertFalse(route.contains("\"Search Offline\""))
    }
}
