package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileCatalogPresentationContractTest {
    @Test
    fun `canonical Mobile Library uses one poster grid for Movies and Series`() {
        val shell = sourceText("src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt")
        val library = sourceText("src/mobile/java/app/ownplay/player/ui/library/MobileLibraryRoute.kt")

        assertTrue(shell.contains("MobileSection.LIBRARY -> MobileLibraryRoute("))
        assertTrue(library.contains("LazyVerticalGrid("))
        assertTrue(library.contains("GridCells.Adaptive(minSize = 104.dp)"))
        assertTrue(library.contains("MobileLibraryFilter.MOVIES -> gridItems("))
        assertTrue(library.contains("MobileLibraryFilter.SERIES -> gridItems("))
        assertTrue(library.contains("MobileLibraryPosterCard("))
        assertTrue(library.contains("aspectRatio(2f / 3f)"))
    }

    @Test
    fun `canonical Mobile Library has no alternate orientation or view-mode presentation`() {
        val library = sourceText("src/mobile/java/app/ownplay/player/ui/library/MobileLibraryRoute.kt")

        assertFalse(library.contains("ORIENTATION_LANDSCAPE"))
        assertFalse(library.contains("isLandscape"))
        assertFalse(library.contains("ContentViewMode"))
        assertFalse(library.contains("OfflineDownloadFeatureRuntime"))
    }
}
