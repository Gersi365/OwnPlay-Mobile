package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandResumeContractTest {
    @Test
    fun `on-demand details expose explicit resume and from-beginning modes`() {
        val startMode = sourceText("src/main/java/app/ownplay/player/ui/OnDemandPlaybackStartMode.kt")
        val movie = sourceText("src/main/java/app/ownplay/player/ui/vod/MovieDetailsPane.kt")
        val series = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesDetailsPane.kt")

        assertTrue(startMode.contains("RESUME"))
        assertTrue(startMode.contains("FROM_BEGINNING"))
        assertTrue(movie.contains("Play from beginning"))
        assertTrue(movie.contains("OnDemandPlaybackStartMode.RESUME"))
        assertTrue(movie.contains("positionMs = 0L"))
        assertTrue(series.contains("Play from beginning"))
        assertTrue(series.contains("OnDemandPlaybackStartMode.RESUME"))
        assertTrue(series.contains("positionMs = 0L"))
    }

    @Test
    fun `details do not clear saved progress before playback starts`() {
        val movie = sourceText("src/main/java/app/ownplay/player/ui/vod/MovieDetailsPane.kt")
        val series = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesDetailsPane.kt")
        val progressPolicy = sourceText("src/main/java/app/ownplay/player/playback/PlaybackProgressPolicy.kt")

        assertFalse(movie.contains("Text(\"Clear progress\")"))
        assertFalse(series.contains("Text(\"Clear\")"))
        assertTrue(progressPolicy.contains("positionMs <= 0L && fallback != null"))
    }

    @Test
    fun `series details expose deterministic latest incomplete episode resume`() {
        val series = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesDetailsPane.kt")

        assertTrue(series.contains("latestIncompleteEpisode"))
        assertTrue(series.contains("progressUpdatedAtEpochMillis ?: Long.MIN_VALUE"))
        assertTrue(series.contains("seriesResumeLabel"))
        assertTrue(series.contains("Resume S"))
    }
}
