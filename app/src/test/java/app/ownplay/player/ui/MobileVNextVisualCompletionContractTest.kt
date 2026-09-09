package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileVNextVisualCompletionContractTest {
    @Test
    fun `Live browse is flat media hierarchy instead of legacy pill and card chrome`() {
        val live = sourceText("src/mobile/java/app/ownplay/player/ui/TargetLiveRoute.kt")

        assertTrue(live.contains("MobileLiveSearchField("))
        assertTrue(live.contains("BasicTextField("))
        assertTrue(live.contains("MobileSearchAction("))
        assertTrue(live.contains("MobileCategoryTab("))
        assertTrue(live.contains("HorizontalDivider("))
        assertTrue(live.contains("LivePreviewPanel("))
        assertTrue(live.contains("EpgPanel("))
        assertTrue(live.contains(".width(2.dp)"))
        assertTrue(live.contains("Color.Transparent"))

        assertFalse(live.contains("MobileCategoryPill("))
        assertFalse(live.contains("FilterChip("))
        assertFalse(live.contains("OutlinedTextField("))
    }

    @Test
    fun `primary shell removes permanent brand header and rounded navigation slab`() {
        val shell = sourceText("src/mobile/java/app/ownplay/player/ui/MobileVNextOwnPlayApp.kt")

        assertTrue(shell.contains("MobileMediaNavigationBar("))
        assertTrue(shell.contains("MobileMediaNavItem("))
        assertTrue(shell.contains(".selectable("))
        assertTrue(shell.contains("HorizontalDivider("))
        assertTrue(shell.contains("MobilePrimaryDestination.LIVE"))
        assertTrue(shell.contains("MobilePrimaryDestination.LIBRARY"))
        assertTrue(shell.contains("MobilePrimaryDestination.SETTINGS"))
        assertTrue(shell.contains("MaterialTheme.colorScheme.primary"))
        assertTrue(shell.contains("MaterialTheme.colorScheme.onSurfaceVariant"))

        assertFalse(shell.contains("MobileVNextHeader("))
        assertFalse(shell.contains("MobileVNextPrimaryNavigationBar("))
        assertFalse(shell.contains("MobileVNextNavItem("))
        assertFalse(shell.contains("NavigationBarItem("))
        assertFalse(shell.contains("NavigationBarItemDefaults"))
    }
}
