package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPortraitSimplificationContractTest {
    @Test
    fun `portrait Settings exposes only contextual app sections in vNext presentation`() {
        val portrait = sourceText("src/main/java/app/ownplay/player/ui/SettingsPortrait.kt")
        val components = sourceText("src/main/java/app/ownplay/player/ui/SettingsComponents.kt")
        val content = sourceText("src/main/java/app/ownplay/player/ui/SettingsContent.kt")
        val backup = sourceText("src/main/java/app/ownplay/player/ui/BackupRestoreSettingsContent.kt")

        assertTrue(portrait.contains("VNextSettingsSection("))
        listOf("Sources", "Downloads", "Backup & Restore", "About").forEach { title ->
            assertTrue(portrait.contains("title = \"$title\""))
        }
        assertTrue(portrait.contains("SourcesSettingsContent("))
        assertTrue(portrait.contains("actionLabel = \"Open downloads\""))
        assertTrue(portrait.contains("BackupRestoreSettingsContent()"))
        assertTrue(portrait.contains("AboutSettingsContent()"))
        assertFalse(portrait.contains("SettingsSectionTitle("))
        assertFalse(portrait.contains("title = \"Content\""))
        assertFalse(portrait.contains("Interface"))
        assertFalse(portrait.contains("Playback"))

        assertTrue(components.contains("internal fun VNextSettingsSection("))
        assertTrue(components.contains("defaultMinSize(minHeight = 56.dp)"))
        assertFalse(components.contains("IconButton("))
        assertFalse(content.contains("HorizontalDivider("))
        assertFalse(backup.contains("HorizontalDivider("))
    }

    @Test
    fun `Settings destinations include scoped Downloads management`() {
        val screen = sourceText("src/main/java/app/ownplay/player/ui/SettingsScreen.kt")

        listOf("HOME", "SOURCES", "LIVE_MANAGEMENT", "DOWNLOADS").forEach { destination ->
            assertTrue(screen.contains(destination))
        }
        assertTrue(screen.contains("SettingsDestination.DOWNLOADS ->"))
        assertTrue(screen.contains("DownloadsSettingsScreen()"))
        assertFalse(screen.contains("SettingsDestination.CONTENT"))
        assertFalse(screen.contains("SettingsDestination.ABOUT"))
        assertFalse(screen.contains("SettingsDestination.PLAYLISTS"))
        assertTrue(screen.contains("onOpenSources = { destination = SettingsDestination.SOURCES }"))
        assertTrue(screen.contains("onOpenDownloads = { destination = SettingsDestination.DOWNLOADS }"))
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
