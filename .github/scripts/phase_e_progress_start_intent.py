from pathlib import Path
import re

VOD = Path("app/src/main/java/app/ownplay/player/ui/vod/VodRoute.kt")
MOVIE_DETAILS = Path("app/src/main/java/app/ownplay/player/ui/vod/MovieDetailsPane.kt")
SERIES = Path("app/src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt")
SERIES_DETAILS = Path("app/src/main/java/app/ownplay/player/ui/series/SeriesDetailsPane.kt")
TEST = Path("app/src/test/java/app/ownplay/player/ui/PlaybackProgressStartIntentContractTest.kt")

movie_details = MOVIE_DETAILS.read_text()
old = "    onClearProgress: () -> Unit,\n    onPlay: (VodMovie) -> Unit,"
new = "    onPlay: (VodMovie) -> Unit,\n    onPlayFromBeginning: (VodMovie) -> Unit,"
assert old in movie_details
movie_details = movie_details.replace(old, new, 1)
movie_clear_pattern = re.compile(
    r"\n            if \(\(movie\.positionMs \?: 0L\) > 0L\) \{\n"
    r"                TextButton\(onClick = onClearProgress\) \{\n"
    r"                    Text\(\"Clear progress\"\)\n"
    r"                \}\n"
    r"            \}\n"
)
movie_details, count = movie_clear_pattern.subn(
    "\n            if (movie.resumeAvailable) {\n"
    "                TextButton(onClick = { onPlayFromBeginning(movie) }) {\n"
    "                    Text(\"Play from beginning\")\n"
    "                }\n"
    "            }\n",
    movie_details,
)
assert count == 1, count
assert "Clear progress" not in movie_details
assert "Play from beginning" in movie_details
MOVIE_DETAILS.write_text(movie_details)

vod = VOD.read_text()
clear_start = vod.index("    fun clearMovieProgress(movie: VodMovie) {")
refresh_start = vod.index("    fun refresh() {", clear_start)
vod = vod[:clear_start] + vod[refresh_start:]
helper = '''    fun playMovie(target: VodMovie, startFromBeginning: Boolean) {
        restoreDetailFocusAfterPlayback = false
        runtime.playbackController.start(
            PlaybackRequest(
                sourceId = sourceId,
                channelId = target.movieId,
                mediaKind = PlaybackMediaKind.MOVIE,
            ),
        )
        runtime.onDemandPresentationSession.showMoviePlayback(
            sourceId = sourceId,
            movie = if (startFromBeginning) {
                target.copy(positionMs = 0L, progressCompleted = false)
            } else {
                target
            },
            returnToLibraryOnDetailBack = returnToLibraryOnDetailBack,
        )
    }

'''
refresh_start = vod.index("    fun refresh() {")
vod = vod[:refresh_start] + helper + vod[refresh_start:]
movie_caller = re.compile(
    r"(?P<indent>\s+)onClearProgress = \{ clearMovieProgress\(movie\) \},\n"
    r"(?P=indent)onPlay = \{ target ->.*?\n"
    r"(?P=indent)\},\n"
    r"(?P=indent)modifier =",
    re.S,
)
def replace_movie_call(match):
    i = match.group("indent")
    return (
        f"{i}onPlay = {{ target -> playMovie(target, startFromBeginning = false) }},\n"
        f"{i}onPlayFromBeginning = {{ target -> playMovie(target, startFromBeginning = true) }},\n"
        f"{i}modifier ="
    )
vod, count = movie_caller.subn(replace_movie_call, vod)
assert count == 2, count
assert "clearMovieProgress" not in vod
assert vod.count("onPlayFromBeginning = { target -> playMovie(target, startFromBeginning = true) }") == 2
VOD.write_text(vod)

series = SERIES.read_text()
play_start = series.index("    fun playEpisode(episode: SeriesEpisode, returnFocusToCatalog: Boolean) {")
download_start = series.index("    fun downloadEpisode(episode: SeriesEpisode) {", play_start)
play_helper = '''    fun playEpisode(
        episode: SeriesEpisode,
        returnFocusToCatalog: Boolean,
        startFromBeginning: Boolean = false,
    ) {
        restoreCatalogFocusAfterPlayback = false
        val playbackEpisode = if (startFromBeginning) {
            episode.copy(positionMs = 0L, progressCompleted = false)
        } else {
            episode
        }
        runtime.playbackController.start(
            PlaybackRequest(
                sourceId = sourceId,
                channelId = episode.episodeId,
                mediaKind = PlaybackMediaKind.SERIES_EPISODE,
                providerStreamId = episode.providerEpisodeId,
                containerExtension = episode.containerExtension,
            ),
        )
        runtime.onDemandPresentationSession.showSeriesPlayback(
            sourceId = sourceId,
            episode = playbackEpisode,
            returnToLibraryOnDetailBack = returnToLibraryOnDetailBack,
            returnToCatalog = returnFocusToCatalog,
            selectedSeasonNumber = selectedSeasonNumber,
            selectedEpisodeId = selectedEpisodeId,
        )
    }

'''
series = series[:play_start] + play_helper + series[download_start:]
clear_episode_pattern = re.compile(
    r"(?P<indent>\s+)onClearProgress = \{ episode ->\n"
    r"(?P=indent)    scope\.launch \{\n"
    r"(?P=indent)        featureRuntime\.clearEpisodeProgress\(sourceId, episode\.episodeId\)\n"
    r"(?P=indent)    \}\n"
    r"(?P=indent)\},"
)
def replace_series_call(match):
    i = match.group("indent")
    return (
        f"{i}onPlayFromBeginning = {{ episode ->\n"
        f"{i}    playEpisode(episode, returnFocusToCatalog = false, startFromBeginning = true)\n"
        f"{i}}},"
    )
series, count = clear_episode_pattern.subn(replace_series_call, series)
assert count == 2, count
assert "clearEpisodeProgress(sourceId, episode.episodeId)" not in series
assert series.count("startFromBeginning = true") >= 2
SERIES.write_text(series)

series_details = SERIES_DETAILS.read_text()
series_details = series_details.replace("onClearProgress", "onPlayFromBeginning")
series_clear_pattern = re.compile(
    r"if \(\(episode\.positionMs \?: 0L\) > 0L\) \{\n"
    r"\s*TextButton\(onClick = onPlayFromBeginning\) \{ Text\(\"Clear\"\) \}\n"
    r"\s*\}"
)
series_details, count = series_clear_pattern.subn(
    'if (episode.resumeAvailable) {\n                    TextButton(onClick = onPlayFromBeginning) { Text("Play from beginning") }\n                }',
    series_details,
)
assert count == 1, count
assert 'Text("Clear")' not in series_details
assert "Play from beginning" in series_details
SERIES_DETAILS.write_text(series_details)

TEST.write_text('''package app.ownplay.player.ui

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
        assertFalse(details.contains("Text(\\\"Clear\\\")"))
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
''')
