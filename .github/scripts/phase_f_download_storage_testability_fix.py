from pathlib import Path
import re

STORAGE = Path("app/src/main/java/app/ownplay/player/download/OfflineDownloadStorage.kt")
TEST = Path("app/src/test/java/app/ownplay/player/download/OfflineDownloadStorageTest.kt")

storage = STORAGE.read_text()
old_signature = '''    internal fun publicRelativePath(
        mediaKind: String,
        seriesTitle: String?,
        seasonNumber: Int?,
    ): String {
        val root = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_ROOT_DIRECTORY"
'''
new_signature = '''    internal fun publicRelativePath(
        mediaKind: String,
        seriesTitle: String?,
        seasonNumber: Int?,
        downloadsDirectory: String,
    ): String {
        val root = "$downloadsDirectory/$PUBLIC_ROOT_DIRECTORY"
'''
assert old_signature in storage
storage = storage.replace(old_signature, new_signature, 1)
old_row_call = '''        publicRelativePath(
            mediaKind = row.mediaKind,
            seriesTitle = row.seriesTitle,
            seasonNumber = row.seasonNumber,
        )
'''
new_row_call = '''        publicRelativePath(
            mediaKind = row.mediaKind,
            seriesTitle = row.seriesTitle,
            seasonNumber = row.seasonNumber,
            downloadsDirectory = Environment.DIRECTORY_DOWNLOADS,
        )
'''
assert old_row_call in storage
storage = storage.replace(old_row_call, new_row_call, 1)
STORAGE.write_text(storage)

test = TEST.read_text()
pattern = re.compile(r'(\s+seasonNumber = (?:null|2|0),\n)(\s+\),)')
test, count = pattern.subn(
    lambda match: match.group(1) + '                downloadsDirectory = "Download",\n' + match.group(2),
    test,
)
assert count == 4, count
assert test.count('downloadsDirectory = "Download"') == 4
TEST.write_text(test)
