package app.ownplay.player.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileOnDemandTvPresentationPurgeContractTest {
    @Test
    fun movieAndSeriesDetailsDoNotRetainTvPresentationBranches() {
        val movie = sourceFile(
            "src/main/java/app/ownplay/player/ui/vod/MovieDetailsPane.kt",
        ).readText()
        val series = sourceFile(
            "src/main/java/app/ownplay/player/ui/series/SeriesDetailsPane.kt",
        ).readText()

        listOf(movie, series).forEach { source ->
            assertFalse(source.contains("UI_MODE_TYPE_TELEVISION"))
            assertFalse(source.contains("LocalConfiguration"))
            assertFalse(source.contains("isTelevision"))
            assertFalse(source.contains("FocusRequester"))
            assertFalse(source.contains("focusRequester"))
            assertFalse(source.contains("focusBackOnEntry"))
            assertFalse(source.contains("withFrameNanos"))
        }
    }

    @Test
    fun onDemandRoutesDoNotPassLegacyFocusEntryFlags() {
        val vod = sourceFile("src/main/java/app/ownplay/player/ui/vod/VodRoute.kt").readText()
        val series = sourceFile("src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt").readText()

        assertFalse(vod.contains("focusBackOnEntry"))
        assertFalse(series.contains("focusBackOnEntry"))
    }

    @Test
    fun mobileDetailsKeepResumeAndCanonicalDownloadActions() {
        val movie = sourceFile(
            "src/main/java/app/ownplay/player/ui/vod/MovieDetailsPane.kt",
        ).readText()
        val series = sourceFile(
            "src/main/java/app/ownplay/player/ui/series/SeriesDetailsPane.kt",
        ).readText()

        listOf(movie, series).forEach { source ->
            assertTrue(source.contains("OnDemandPlaybackStartMode.RESUME"))
            assertTrue(source.contains("OnDemandPlaybackStartMode.FROM_BEGINNING"))
            assertTrue(source.contains("DownloadStates.QUEUED"))
            assertTrue(source.contains("DownloadStates.DOWNLOADING"))
            assertTrue(source.contains("DownloadStates.PAUSED"))
            assertTrue(source.contains("DownloadStates.FAILED"))
            assertTrue(source.contains("DownloadStates.COMPLETED"))
        }
        assertTrue(movie.contains("Play from beginning"))
        assertTrue(series.contains("Play from beginning"))
        assertTrue(movie.contains("Resume Offline"))
        assertTrue(series.contains("Resume Offline"))
    }

    private fun sourceFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.exists()) return direct
        return File("app/$relativePath")
    }
}
