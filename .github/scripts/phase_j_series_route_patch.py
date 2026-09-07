from pathlib import Path

path = Path("app/src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt")
text = path.read_text()

old_back = '''            selectedSeasonNumber != null -> {
                selectedSeasonNumber = null
                selectedEpisodeId = null
                runtime.onDemandPresentationSession.updateSeriesSelection(
                    seasonNumber = null,
                    episodeId = null,
                )
            }
'''
if text.count(old_back) != 1:
    raise SystemExit(f"expected one season back-navigation block, found {text.count(old_back)}")
text = text.replace(old_back, "")

old_callback = '''            onEpisodeSelected = {
                selectedEpisodeId = it
                runtime.onDemandPresentationSession.updateSeriesSelection(selectedSeasonNumber, it)
            },
'''
new_callback = '''            onEpisodeSelected = { seasonNumber, episodeId ->
                selectedSeasonNumber = seasonNumber
                selectedEpisodeId = episodeId
                runtime.onDemandPresentationSession.updateSeriesSelection(seasonNumber, episodeId)
            },
'''
count = text.count(old_callback)
if count != 2:
    raise SystemExit(f"expected two episode selection callbacks, found {count}")
text = text.replace(old_callback, new_callback)

path.write_text(text)
