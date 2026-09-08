package app.ownplay.player.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileDownloadsTvFocusPurgeContractTest {
    @Test
    fun downloadsPresentationDoesNotRetainTvFocusRouting() {
        val downloads = sourceFile(
            "src/main/java/app/ownplay/player/ui/DownloadsSettingsScreen.kt",
        ).readText()

        assertFalse(downloads.contains("UI_MODE_TYPE_TELEVISION"))
        assertFalse(downloads.contains("LocalConfiguration"))
        assertFalse(downloads.contains("FocusRequester"))
        assertFalse(downloads.contains("focusBackOnEntry"))
        assertFalse(downloads.contains("OfflineMediaTvFocusPolicy"))
        assertFalse(downloads.contains("registerFocusReturn"))
        assertTrue(downloads.contains("registerPlaybackClosed"))
    }

    @Test
    fun playbackCloseBridgeKeepsRefreshSignalWithoutFocusNaming() {
        val bridge = sourceFile(
            "src/main/java/app/ownplay/player/ui/DownloadPlaybackBridge.kt",
        ).readText()

        assertTrue(bridge.contains("registerPlaybackClosed"))
        assertTrue(bridge.contains("notifyPlaybackClosed"))
        assertFalse(bridge.contains("FocusReturn"))
        assertFalse(bridge.contains("focusReturn"))
    }

    @Test
    fun downloadsTvFocusHelperAndTestStayRemoved() {
        listOf(
            "src/main/java/app/ownplay/player/ui/OfflineMediaTvFocusPolicy.kt",
            "src/test/java/app/ownplay/player/ui/OfflineMediaTvFocusPolicyTest.kt",
        ).forEach { relativePath ->
            assertFalse("TV focus residue must stay removed: $relativePath", sourceFile(relativePath).isFile)
        }
    }

    private fun sourceFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.exists()) return direct
        return File("app/$relativePath")
    }
}
