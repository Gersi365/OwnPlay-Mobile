package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressStartIntentContractTest {
    @Test
    fun `movie resume keeps saved progress and beginning uses transient zero position`() {
        val details = sourceText("src/main/java/app/ownplay/player/ui/vod/MovieDetailsPane.kt")
        val route = sourceText("src/main/java/app/ownplay/player/ui/vod/VodRoute.kt")

        assertTrue(details.contains("movie.resumeAvailable"))
        assertTrue(details.contains("Play from beginning"))
        assertFalse(details.contains("Clear progress"))
        assertTrue(route.contains("target.copy(positionMs = 0L, progressCompleted = false)"))
        assertTrue(route.contains("startFromBeginning = false"))
        assertTrue(route.contains("startFromBeginning = true"))
        assertFalse(route.contains("clearMovieProgress"))
    }

    @Test
    fun `series resume keeps saved progress and beginning uses transient zero position`() {
        val details = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesDetailsPane.kt")
        val route = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt")

        assertTrue(details.contains("episode.resumeAvailable"))
        assertTrue(details.contains("Play from beginning"))
        assertFalse(details.contains("Text(\"Clear\")"))
        assertTrue(route.contains("episode.copy(positionMs = 0L, progressCompleted = false)"))
        assertTrue(route.contains("startFromBeginning: Boolean = false"))
        assertFalse(route.contains("clearEpisodeProgress(sourceId, episode.episodeId)"))
    }

    @Test
    fun `Downloads exposes offline resume and transient restart without clearing saved progress`() {
        val downloads = sourceText("src/main/java/app/ownplay/player/ui/DownloadsSettingsScreen.kt")
        val activity = sourceText("src/main/java/app/ownplay/player/MainActivity.kt")
        val playback = sourceText("src/main/java/app/ownplay/player/ui/library/LibraryPlaybackScreen.kt")

        assertTrue(downloads.contains("runtime.playbackProgress(download.downloadId)"))
        assertTrue(downloads.contains("\"Resume Offline\""))
        assertTrue(downloads.contains("label = \"Play from beginning\""))
        assertTrue(downloads.contains("startFromBeginning = startFromBeginning"))

        assertTrue(activity.contains("downloadRuntime.playbackProgress(download.downloadId)"))
        assertTrue(activity.contains("if (startFromBeginning)"))
        assertTrue(activity.contains("0L"))
        assertTrue(activity.contains("takeIf { !it.completed }"))
        assertTrue(playback.contains("session.initialPositionMs"))

        assertFalse(downloads.contains("clearProgress"))
        assertFalse(activity.contains("clearProgress"))
        assertFalse(playback.contains("clearProgress"))
    }
}
