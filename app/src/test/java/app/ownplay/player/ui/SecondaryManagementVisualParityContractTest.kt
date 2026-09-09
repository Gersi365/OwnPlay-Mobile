package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondaryManagementVisualParityContractTest {
    @Test
    fun `Downloads management uses vNext actions while preserving canonical semantics`() {
        val downloads = sourceText("src/main/java/app/ownplay/player/ui/DownloadsSettingsScreen.kt")

        assertTrue(downloads.contains("VNextMediaIconAction("))
        assertTrue(downloads.contains("VNextMediaPrimaryAction("))
        assertTrue(downloads.contains("VNextMediaSecondaryAction("))
        assertTrue(downloads.contains("\"Resume Offline\""))
        assertTrue(downloads.contains("\"Play Offline\""))
        assertTrue(downloads.contains("label = \"Play from beginning\""))
        assertTrue(downloads.contains("runtime.pause(download.downloadId)"))
        assertTrue(downloads.contains("runtime.resume(download.downloadId)"))
        assertTrue(downloads.contains("runtime.retry(download.downloadId)"))
        assertTrue(downloads.contains("runtime.remove(download.downloadId)"))
        assertFalse(downloads.contains("IconButton(onClick = onPause)"))
        assertFalse(downloads.contains("IconButton(onClick = onResume)"))
        assertFalse(downloads.contains("IconButton(onClick = onRetry)"))
    }

    @Test
    fun `Sources management uses color driven vNext controls without changing source actions`() {
        val sources = sourceText("src/main/java/app/ownplay/player/ui/PlaylistSettingsScreen.kt")
        val wrapper = sourceText("src/main/java/app/ownplay/player/ui/SettingsPlaylist.kt")

        assertTrue(sources.contains("VNextMediaPrimaryAction("))
        assertTrue(sources.contains("VNextMediaPill("))
        assertTrue(sources.contains("role = Role.RadioButton"))
        assertTrue(sources.contains("runtime.refreshSource(summary.sourceId)"))
        assertTrue(sources.contains("runtime.retryPendingSource(summary.sourceId)"))
        assertTrue(sources.contains("runtime.deleteSource(target.sourceId)"))
        assertFalse(sources.contains("RadioButton("))
        assertFalse(sources.contains("HorizontalDivider()"))
        assertTrue(wrapper.contains("VNextMediaSecondaryAction("))
        assertFalse(wrapper.contains("TextButton(onClick = onBack)"))
    }
}
