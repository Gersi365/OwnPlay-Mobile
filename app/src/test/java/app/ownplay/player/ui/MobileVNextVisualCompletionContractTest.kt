package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileVNextVisualCompletionContractTest {
    @Test
    fun `Live browse uses vNext media first controls instead of management chrome`() {
        val live = sourceText("src/mobile/java/app/ownplay/player/ui/TargetLiveRoute.kt")

        assertTrue(live.contains("MobileLiveSearchField("))
        assertTrue(live.contains("BasicTextField("))
        assertTrue(live.contains("MobileCategoryPill("))
        assertTrue(live.contains("verticalArrangement = Arrangement.spacedBy(6.dp)"))
        assertTrue(live.contains("LivePreviewPanel("))
        assertTrue(live.contains("EpgPanel("))

        assertFalse(live.contains("FilterChip("))
        assertFalse(live.contains("OutlinedTextField("))
        assertFalse(live.contains("HorizontalDivider("))
    }

    @Test
    fun `primary shell chrome is custom stable and color driven`() {
        val shell = sourceText("src/mobile/java/app/ownplay/player/ui/MobileVNextOwnPlayApp.kt")

        assertTrue(shell.contains("MobileVNextNavItem("))
        assertTrue(shell.contains(".selectable("))
        assertTrue(shell.contains("MobilePrimaryDestination.LIVE"))
        assertTrue(shell.contains("MobilePrimaryDestination.LIBRARY"))
        assertTrue(shell.contains("MobilePrimaryDestination.SETTINGS"))
        assertTrue(shell.contains("MaterialTheme.colorScheme.primary"))
        assertTrue(shell.contains("MaterialTheme.colorScheme.onSurfaceVariant"))

        assertFalse(shell.contains("NavigationBar("))
        assertFalse(shell.contains("NavigationBarItem("))
        assertFalse(shell.contains("NavigationBarItemDefaults"))
    }
}
