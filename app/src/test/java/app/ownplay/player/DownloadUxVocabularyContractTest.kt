package app.ownplay.player

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUxVocabularyContractTest {
    @Test
    fun `public download destination uses OwnPlay Downloads vocabulary`() {
        val downloadsSettings = sourceText("src/main/java/app/ownplay/player/ui/DownloadsSettingsScreen.kt")
        val movieDetails = sourceText("src/main/java/app/ownplay/player/ui/vod/MovieDetailsPane.kt")
        val seriesDetails = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesDetailsPane.kt")
        val libraryOffline = sourceText("src/main/java/app/ownplay/player/ui/library/LibraryOfflinePresentation.kt")
        val sources = listOf(downloadsSettings, movieDetails, seriesDetails, libraryOffline)

        sources.forEach { source ->
            assertFalse(source.contains("Phone Downloads"))
            assertFalse(source.contains("phone Downloads"))
        }
        assertTrue(downloadsSettings.contains("OwnPlay Downloads"))
        assertTrue(movieDetails.contains("Downloaded · OwnPlay Downloads"))
        assertTrue(movieDetails.contains("Saving to OwnPlay Downloads"))
        assertTrue(seriesDetails.contains("Downloaded · OwnPlay Downloads"))
        assertTrue(libraryOffline.contains("Local file · OwnPlay Downloads"))
    }

    @Test
    fun `canonical download action vocabulary remains intact`() {
        val downloadsSettings = sourceText("src/main/java/app/ownplay/player/ui/DownloadsSettingsScreen.kt")
        val movieDetails = sourceText("src/main/java/app/ownplay/player/ui/vod/MovieDetailsPane.kt")
        val seriesDetails = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesDetailsPane.kt")
        val sources = listOf(downloadsSettings, movieDetails, seriesDetails)

        listOf("Download", "Pause", "Resume", "Retry").forEach { action ->
            assertTrue(sources.any { source -> source.contains("\"$action\"") })
        }
        assertTrue(movieDetails.contains("\"Play Offline\""))
        assertTrue(movieDetails.contains("\"Resume Offline\""))
        assertTrue(seriesDetails.contains("\"Play Offline\""))
        assertTrue(seriesDetails.contains("\"Resume Offline\""))
        sources.forEach { source -> assertFalse(source.contains("Resume DL")) }
    }
}
