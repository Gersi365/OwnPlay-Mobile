package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPortraitSimplificationContractTest {
    @Test
    fun `portrait Settings exposes only real top-level sections`() {
        val portrait = sourceText("src/main/java/app/ownplay/player/ui/SettingsPortrait.kt")

        listOf("Sources", "Downloads", "Backup & Restore", "About").forEach { title ->
            assertTrue(portrait.contains("SettingsSectionTitle(\"$title\")"))
        }
        assertTrue(portrait.contains("SourcesSettingsContent("))
        assertTrue(portrait.contains("BackupRestoreSettingsContent()"))
        assertTrue(portrait.contains("AboutSettingsContent()"))
        assertFalse(portrait.contains("CompactSettingsSection("))
        assertFalse(portrait.contains("title = \"Content\""))
        assertFalse(portrait.contains("Interface"))
        assertFalse(portrait.contains("Playback"))
    }

    @Test
    fun `Settings destinations match the simplified nested screens`() {
        val screen = sourceText("src/main/java/app/ownplay/player/ui/SettingsScreen.kt")

        listOf("HOME", "SOURCES", "DOWNLOADS", "LIVE_MANAGEMENT").forEach { destination ->
            assertTrue(screen.contains(destination))
        }
        assertFalse(screen.contains("SettingsDestination.CONTENT"))
        assertFalse(screen.contains("SettingsDestination.ABOUT"))
        assertFalse(screen.contains("SettingsDestination.PLAYLISTS"))
        assertTrue(screen.contains("onOpenSources = { destination = SettingsDestination.SOURCES }"))
        assertTrue(screen.contains("BackHandler(enabled = destination != SettingsDestination.HOME)"))
    }

    @Test
    fun `backup is separate from Sources and existing backup implementation remains reused`() {
        val content = sourceText("src/main/java/app/ownplay/player/ui/SettingsContent.kt")
        val portrait = sourceText("src/main/java/app/ownplay/player/ui/SettingsPortrait.kt")

        assertTrue(content.contains("internal fun SourcesSettingsContent("))
        assertTrue(content.contains("title = \"Sources\""))
        assertTrue(content.contains("title = \"Live organization\""))
        assertFalse(content.contains("BackupRestoreSettingsContent()"))
        assertTrue(portrait.contains("BackupRestoreSettingsContent()"))
    }
}
