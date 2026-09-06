package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadActionConsistencyContractTest {
    @Test
    fun `movie details use canonical download actions and repository retry semantics`() {
        val details = sourceText("src/main/java/app/ownplay/player/ui/vod/MovieDetailsPane.kt")

        assertTrue(details.contains("offlineCopyAvailable = !isTelevision && download?.state == DownloadStates.COMPLETED"))
        assertTrue(details.contains("DownloadStates.QUEUED -> \"Pause\""))
        assertTrue(details.contains("DownloadStates.DOWNLOADING -> \"Pause\""))
        assertTrue(details.contains("DownloadStates.PAUSED -> \"Resume\""))
        assertTrue(details.contains("DownloadStates.FAILED -> \"Retry\""))
        assertTrue(details.contains("offlineCopyAvailable -> \"Play Offline\""))
        assertTrue(details.contains("DownloadStates.FAILED -> onRetryDownload(download)"))
        assertTrue(details.contains("null -> onDownload(target)"))
        assertTrue(details.contains("onRemoveDownload(managedDownload)"))
        assertTrue(details.contains("contentDescription = \"Remove download\""))
        assertFalse(details.contains("Retry download"))
    }

    @Test
    fun `series details use the same canonical download actions`() {
        val details = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesDetailsPane.kt")

        assertTrue(details.contains("offlineCopyAvailable = !isTelevision && download?.state == DownloadStates.COMPLETED"))
        assertTrue(details.contains("-> \"Pause\""))
        assertTrue(details.contains("DownloadStates.PAUSED -> \"Resume\""))
        assertTrue(details.contains("DownloadStates.FAILED -> \"Retry\""))
        assertTrue(details.contains("offlineCopyAvailable -> \"Play Offline\""))
        assertTrue(details.contains("DownloadStates.FAILED -> onRetryDownload(download)"))
        assertTrue(details.contains("null -> onDownload()"))
        assertTrue(details.contains("onRemoveDownload(download)"))
        assertTrue(details.contains("contentDescription = \"Remove episode download\""))
        assertFalse(details.contains("Resume DL"))
    }

    @Test
    fun `online detail routes wire retry and remove to the download repository`() {
        val vod = sourceText("src/main/java/app/ownplay/player/ui/vod/VodRoute.kt")
        val series = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt")

        listOf(vod, series).forEach { route ->
            assertTrue(route.contains("downloadRuntime.retry(download.downloadId)"))
            assertTrue(route.contains("downloadRuntime.remove(download.downloadId)"))
            assertTrue(route.contains("onRetryDownload = ::retryDownload"))
            assertTrue(route.contains("onRemoveDownload = ::removeDownload"))
        }
    }

    @Test
    fun `downloaded media remains the canonical action reference`() {
        val library = sourceText("src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt")
        val seriesLibrary = sourceText("src/main/java/app/ownplay/player/ui/library/LibrarySeriesComponents.kt")

        assertTrue(library.contains("downloadRuntime.retry(download.downloadId)"))
        assertTrue(library.contains("downloadRuntime.remove(download.downloadId)"))
        assertTrue(library.contains("Text(\"Play Offline\")"))
        assertTrue(seriesLibrary.contains("Text(\"Play Offline\")"))
        assertTrue(seriesLibrary.contains("Text(\"Pause\")"))
        assertTrue(seriesLibrary.contains("Text(\"Resume\")"))
        assertTrue(seriesLibrary.contains("Text(\"Retry\")"))
        assertTrue(seriesLibrary.contains("contentDescription = \"Remove episode download\""))
    }
}
