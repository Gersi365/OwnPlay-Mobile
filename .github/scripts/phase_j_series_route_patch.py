from pathlib import Path
import re

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

pattern = re.compile(
    r'(?P<indent>\s*)onEpisodeSelected = \{\n'
    r'(?P=indent)    selectedEpisodeId = it\n'
    r'(?P=indent)    runtime\.onDemandPresentationSession\.updateSeriesSelection\(selectedSeasonNumber, it\)\n'
    r'(?P=indent)\},'
)

def replace_callback(match: re.Match[str]) -> str:
    indent = match.group("indent")
    return (
        f"{indent}onEpisodeSelected = {{ seasonNumber, episodeId ->\n"
        f"{indent}    selectedSeasonNumber = seasonNumber\n"
        f"{indent}    selectedEpisodeId = episodeId\n"
        f"{indent}    runtime.onDemandPresentationSession.updateSeriesSelection(seasonNumber, episodeId)\n"
        f"{indent}}},"
    )

text, count = pattern.subn(replace_callback, text)
if count != 2:
    raise SystemExit(f"expected two episode selection callbacks, found {count}")

path.write_text(text)
