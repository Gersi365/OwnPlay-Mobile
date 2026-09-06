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
    fun `offline playback still derives resume position without clearing persisted progress`() {
        val library = sourceText("src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt")
        val playback = sourceText("src/main/java/app/ownplay/player/ui/library/LibraryPlaybackScreen.kt")

        assertTrue(library.contains("downloadRuntime.playbackProgress(download.downloadId)"))
        assertTrue(library.contains("takeIf { !it.completed }"))
        assertTrue(playback.contains("session.initialPositionMs"))
        assertFalse(playback.contains("clearProgress"))
    }
}
