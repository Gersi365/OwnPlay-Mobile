package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileLibraryVisualCompletionContractTest {
    @Test
    fun `Library uses flat catalog controls and keeps poster first grid`() {
        val library = sourceText("src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt")

        assertTrue(library.contains("LibraryTextTab("))
        assertTrue(library.contains("LibrarySearchField("))
        assertTrue(library.contains("BasicTextField("))
        assertTrue(library.contains("HorizontalDivider("))
        assertTrue(library.contains("LazyVerticalGrid("))
        assertTrue(library.contains("GridCells.Adaptive(minSize = OwnPlayMediaLayout.MinimumPosterWidthDp.dp)"))
        assertTrue(library.contains("LibraryUnifiedContinueWatchingStrip("))
        assertFalse(library.contains("VNextMediaPill("))
        assertFalse(library.contains("VNextMediaSearchField("))
        assertFalse(library.contains("VNextMediaIconAction("))
        assertFalse(library.contains("FilterChip("))
        assertFalse(library.contains("OutlinedTextField("))
    }

    @Test
    fun `on demand details keep canonical actions and vocabulary`() {
        val movie = sourceText("src/main/java/app/ownplay/player/ui/vod/MovieDetailsPane.kt")
        val series = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesDetailsPane.kt")

        listOf(movie, series).forEach { details ->
            assertTrue(details.contains("VNextMediaPrimaryAction("))
            assertTrue(details.contains("VNextMediaSecondaryAction("))
            assertTrue(details.contains("VNextMediaIconAction("))
            assertFalse(details.contains("FilledTonalButton("))
            assertFalse(details.contains("FilterChip("))
        }
        assertTrue(movie.contains("\"Play from beginning\""))
        assertTrue(series.contains("\"Play from beginning\""))
        assertTrue(movie.contains("\"Play Offline\""))
        assertTrue(series.contains("\"Play Offline\""))
    }
}
