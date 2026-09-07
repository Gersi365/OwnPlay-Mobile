from pathlib import Path

STORAGE = Path("app/src/main/java/app/ownplay/player/download/OfflineDownloadStorage.kt")
TEST = Path("app/src/test/java/app/ownplay/player/download/OfflineDownloadStorageTest.kt")

storage = STORAGE.read_text()

old_import = "import app.ownplay.player.persistence.download.MediaDownloadEntity\n"
new_import = (
    "import app.ownplay.player.persistence.download.DownloadMediaKinds\n"
    "import app.ownplay.player.persistence.download.MediaDownloadEntity\n"
)
assert old_import in storage
storage = storage.replace(old_import, new_import, 1)

old_constants = '''    private const val PRIVATE_DIRECTORY = "offline"
    private const val MEDIASTORE_URI_PREFIX = "content://media/"
    private const val PENDING_DOWNLOAD_MARKER_PREFIX = "ownplay://offline-download/"
'''
new_constants = '''    private const val PRIVATE_DIRECTORY = "offline"
    private const val MEDIASTORE_URI_PREFIX = "content://media/"
    private const val PENDING_DOWNLOAD_MARKER_PREFIX = "ownplay://offline-download/"
    private const val PUBLIC_ROOT_DIRECTORY = "OwnPlay Downloads"
    private const val PUBLIC_MOVIES_DIRECTORY = "Movies"
    private const val PUBLIC_SERIES_DIRECTORY = "Series"
'''
assert old_constants in storage
storage = storage.replace(old_constants, new_constants, 1)

old_relative_path = "            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)\n"
new_relative_path = "            put(MediaStore.Downloads.RELATIVE_PATH, publicRelativePath(row))\n"
assert old_relative_path in storage
storage = storage.replace(old_relative_path, new_relative_path, 1)

anchor = '''    internal fun pendingDownloadMarker(downloadId: String): String =
        "$PENDING_DOWNLOAD_MARKER_PREFIX$downloadId"
'''
helper = '''    internal fun publicRelativePath(
        mediaKind: String,
        seriesTitle: String?,
        seasonNumber: Int?,
    ): String {
        val root = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_ROOT_DIRECTORY"
        return when (mediaKind) {
            DownloadMediaKinds.MOVIE -> "$root/$PUBLIC_MOVIES_DIRECTORY"
            DownloadMediaKinds.SERIES_EPISODE -> {
                val seriesDirectory = safeFileStem(
                    seriesTitle?.takeIf(String::isNotBlank) ?: PUBLIC_SERIES_DIRECTORY,
                )
                val season = (seasonNumber ?: 0)
                    .coerceAtLeast(0)
                    .toString()
                    .padStart(2, '0')
                "$root/$PUBLIC_SERIES_DIRECTORY/$seriesDirectory/Season $season"
            }
            else -> throw IllegalArgumentException("Unsupported download media kind: $mediaKind")
        }
    }

    internal fun publicRelativePath(row: MediaDownloadEntity): String =
        publicRelativePath(
            mediaKind = row.mediaKind,
            seriesTitle = row.seriesTitle,
            seasonNumber = row.seasonNumber,
        )

'''
assert anchor in storage
storage = storage.replace(anchor, helper + anchor, 1)

assert "RELATIVE_PATH, publicRelativePath(row)" in storage
assert "OwnPlay Downloads" in storage
assert "Season $season" in storage
STORAGE.write_text(storage)

test = TEST.read_text()
assert "DownloadMediaKinds" not in test
old_test_import = "package app.ownplay.player.download\n\nimport java.util.Locale\n"
new_test_import = (
    "package app.ownplay.player.download\n\n"
    "import app.ownplay.player.persistence.download.DownloadMediaKinds\n"
    "import java.util.Locale\n"
)
assert old_test_import in test
test = test.replace(old_test_import, new_test_import, 1)

assert test.rstrip().endswith("}")
body = test.rstrip()[:-1]
extra = r'''

    @Test
    fun moviePublicDownloadsPathUsesOwnPlayMoviesHierarchy() {
        assertEquals(
            "Download/OwnPlay Downloads/Movies",
            OfflineDownloadStorage.publicRelativePath(
                mediaKind = DownloadMediaKinds.MOVIE,
                seriesTitle = null,
                seasonNumber = null,
            ),
        )
    }

    @Test
    fun seriesPublicDownloadsPathUsesSanitizedSeriesAndSeasonHierarchy() {
        assertEquals(
            "Download/OwnPlay Downloads/Series/My Series/Season 02",
            OfflineDownloadStorage.publicRelativePath(
                mediaKind = DownloadMediaKinds.SERIES_EPISODE,
                seriesTitle = "My/Series:*?",
                seasonNumber = 2,
            ),
        )
    }

    @Test
    fun seriesSpecialsUseSeasonZeroHierarchy() {
        assertEquals(
            "Download/OwnPlay Downloads/Series/Show Name/Season 00",
            OfflineDownloadStorage.publicRelativePath(
                mediaKind = DownloadMediaKinds.SERIES_EPISODE,
                seriesTitle = "Show Name",
                seasonNumber = 0,
            ),
        )
    }

    @Test
    fun missingSeriesMetadataFallsBackDeterministically() {
        assertEquals(
            "Download/OwnPlay Downloads/Series/Series/Season 00",
            OfflineDownloadStorage.publicRelativePath(
                mediaKind = DownloadMediaKinds.SERIES_EPISODE,
                seriesTitle = null,
                seasonNumber = null,
            ),
        )
    }
'''
TEST.write_text(body + extra + "}\n")
