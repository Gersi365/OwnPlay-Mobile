package app.ownplay.player.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileLegacyPresentationPurgeContractTest {
    @Test
    fun dormantLegacyPresentationFilesAreNotRetained() {
        val removed = listOf(
            "src/main/java/app/ownplay/player/ui/OwnPlayApp.kt",
            "src/main/java/app/ownplay/player/ui/OrientationSetupScreen.kt",
            "src/main/java/app/ownplay/player/ui/PlaybackOriginBadge.kt",
            "src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt",
            "src/main/java/app/ownplay/player/ui/library/LibraryOfflinePresentation.kt",
            "src/main/java/app/ownplay/player/ui/library/LibraryPlaybackPresentationSession.kt",
            "src/main/java/app/ownplay/player/ui/library/LibrarySeriesComponents.kt",
            "src/main/java/app/ownplay/player/ui/library/LibrarySeriesGrouping.kt",
            "src/main/java/app/ownplay/player/ui/live/PortraitLiveViewModes.kt",
            "src/main/java/app/ownplay/player/ui/live/LiveBrowseHierarchy.kt",
            "src/main/java/app/ownplay/player/ui/live/LiveBrowseScreen.kt",
            "src/main/java/app/ownplay/player/ui/view/ContentViewMode.kt",
        )

        removed.forEach { relativePath ->
            assertFalse("Legacy presentation source must stay removed: $relativePath", sourceFile(relativePath).isFile)
        }
    }

    @Test
    fun mobileEntryUsesOnlyCanonicalShellAndLibrary() {
        val target = sourceFile("src/mobile/java/app/ownplay/player/ui/TargetOwnPlayApp.kt").readText()
        val shell = sourceFile("src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt").readText()

        assertTrue(target.contains("MobileOwnPlayApp("))
        assertFalse(Regex("(?m)^\\s*OwnPlayApp\\(").containsMatchIn(target))
        assertTrue(shell.contains("MobileLibraryRoute("))
        assertFalse(shell.contains("UnifiedLibraryRoute"))
        assertFalse(shell.contains("ContentViewMode"))
    }

    @Test
    fun startupAndLiveManagementUseCanonicalMobilePresentation() {
        val activity = sourceFile("src/main/java/app/ownplay/player/MainActivity.kt").readText()
        val management = sourceFile("src/main/java/app/ownplay/player/ui/LiveManagementScreen.kt").readText()

        assertFalse(activity.contains("OrientationSetupLoadingSurface"))
        assertFalse(activity.contains("MobileStartupLoadingSurface"))
        assertTrue(management.contains("Visible Categories"))
        assertTrue(management.contains("Hidden Categories"))
        assertTrue(management.contains("Custom Groups"))
        assertTrue(management.contains("Visible Channels"))
        assertTrue(management.contains("Hidden Channels"))
        assertFalse(management.contains("LiveBrowseScreen("))
        assertFalse(management.contains("LocalConfiguration"))
        assertFalse(management.contains("UI_MODE_TYPE_TELEVISION"))
    }

    @Test
    fun activeMobileRuntimeDoesNotRouteThroughTvTargetProfiles() {
        val activity = sourceFile("src/main/java/app/ownplay/player/MainActivity.kt").readText()
        val root = sourceFile("src/main/java/app/ownplay/player/ui/OwnPlayRoot.kt").readText()

        listOf(activity, root).forEach { source ->
            assertFalse(source.contains("IS_TV_BUILD"))
            assertFalse(source.contains("AppDeviceProfile"))
            assertFalse(source.contains("TvRemote"))
            assertFalse(source.contains("TvPlaybackLifecyclePolicy"))
        }
        assertFalse(activity.contains("setDpadMode"))
        assertFalse(activity.contains("updatePictureInPictureEnabled"))
        assertFalse(activity.contains("updateFullscreenSensorRotationEnabled"))
        assertFalse(root.contains("FocusDirection"))
        assertFalse(root.contains("moveFocus"))
    }

    @Test
    fun onDemandRoutesDoNotReintroduceAlternateCatalogPresentation() {
        val vod = sourceFile("src/main/java/app/ownplay/player/ui/vod/VodRoute.kt").readText()
        val series = sourceFile("src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt").readText()

        listOf(vod, series).forEach { source ->
            assertFalse(source.contains("ORIENTATION_LANDSCAPE"))
            assertFalse(source.contains("isLandscape"))
            assertFalse(source.contains("LocalConfiguration"))
        }
        assertFalse(vod.contains("MoviesCatalogContent"))
        assertFalse(vod.contains("MovieCategoryRail"))
        assertFalse(vod.contains("onOpenLive"))
        assertFalse(vod.contains("onOpenSeries"))
        assertFalse(vod.contains("UNUSED_PARAMETER"))
        assertFalse(series.contains("SeriesCatalogPane"))
    }

    @Test
    fun onDemandSessionRetainsOnlyDetailAndPlaybackPresentation() {
        val session = sourceFile(
            "src/main/java/app/ownplay/player/playback/OnDemandPresentationSession.kt",
        ).readText()
        val series = sourceFile("src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt").readText()

        assertFalse(session.contains("showMovieCatalog"))
        assertFalse(session.contains("showSeriesCatalog"))
        assertFalse(session.contains("seriesPlaybackReturnsToCatalog"))
        assertFalse(session.contains("returnToCatalog"))
        assertFalse(series.contains("returnToCatalog"))
    }

    @Test
    fun mobileFullscreenDoesNotRetainPlaybackOriginChrome() {
        val activity = sourceFile("src/main/java/app/ownplay/player/MainActivity.kt").readText()
        val shell = sourceFile("src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt").readText()

        listOf(activity, shell).forEach { source ->
            assertFalse(source.contains("PlaybackOriginBadge"))
            assertFalse(source.contains("resolvedOrigin.collectAsState"))
        }
    }

    @Test
    fun liveRouteDoesNotRetainDeadOnDemandNavigationCallbacks() {
        val shell = sourceFile("src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt").readText()
        val liveEntry = sourceFile("src/mobile/java/app/ownplay/player/ui/LiveRoute.kt").readText()
        val liveTarget = sourceFile("src/mobile/java/app/ownplay/player/ui/TargetLiveRoute.kt").readText()
        val liveSection = shell
            .substringAfter("MobileSection.LIVE -> {")
            .substringBefore("MobileSection.LIBRARY ->")

        listOf(liveEntry, liveTarget).forEach { source ->
            assertFalse(source.contains("onOpenMovies"))
            assertFalse(source.contains("onOpenSeries"))
        }
        assertFalse(liveSection.contains("onOpenMovies ="))
        assertFalse(liveSection.contains("onOpenSeries ="))
        assertTrue(liveSection.contains("onOpenSettings = ::openSettings"))
    }

    private fun sourceFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.exists()) return direct
        return File("app/$relativePath")
    }
}
