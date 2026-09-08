package app.ownplay.player

import app.ownplay.player.testing.normalizedSource
import app.ownplay.player.testing.sourceBlockAfter
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellLifecycleRegressionTest {
    @Test
    fun mainActivityUsesProcessScopedRuntimeAndDoesNotCloseIt() {
        val source = sourceText("src/main/java/app/ownplay/player/MainActivity.kt")
        assertTrue(source.contains("runtime = (application as OwnPlayApplication).runtime"))

        val onDestroy = sourceBlockAfter(source, "override fun onDestroy()")
        assertFalse(onDestroy.contains("runtime.close()"))
    }

    @Test
    fun mobileActiveSourceHelperPersistsAndDoesNotRecurse() {
        activeShellPaths.forEach { path ->
            val source = sourceText(path)
            val helper = sourceBlockAfter(
                source,
                "fun rememberActiveSource(sourceId: String?)",
            )

            assertTrue("$path must update local active source", helper.contains("activeSourceId = sourceId"))
            assertTrue(
                "$path must persist active source",
                helper.contains("activePlaylistStore.set(sourceId)"),
            )
            assertFalse(
                "$path must not recursively call rememberActiveSource",
                helper.contains("rememberActiveSource(sourceId)"),
            )

            assertEquals(
                "$path must route sourceId selection through rememberActiveSource",
                1,
                Regex("activeSourceId\\s*=\\s*sourceId").findAll(source).count(),
            )
        }
    }

    @Test
    fun mobileWaitsForPersistedSelectionBeforeResolvingFallback() {
        activeShellPaths.forEach { path ->
            val normalized = normalizedSource(sourceText(path))
            assertTrue(
                "$path must not resolve the first playlist while DataStore selection is Loading",
                normalized.contains(
                    "val persistedSelection = activePlaylistSelection as? ActivePlaylistSelection.Ready ?: return@LaunchedEffect",
                ),
            )
            assertTrue(
                "$path must refresh only after a resolved active source changes",
                normalized.contains(
                    "if (resolvedSourceId != null && previousSourceId != resolvedSourceId) { runtime.onActiveSourceSelected(resolvedSourceId)",
                ),
            )
        }
    }

    @Test
    fun mobileLiveSyncStatusIsScopedToDisplayedSource() {
        listOf(
            "src/mobile/java/app/ownplay/player/ui/TargetLiveRoute.kt",
        ).forEach { path ->
            val normalized = normalizedSource(sourceText(path))
            assertTrue(
                "$path must ignore sync status emitted by another playlist",
                normalized.contains("syncState.sourceId == sourceId"),
            )
        }
    }

    @Test
    fun mobileLiveBrowsingHasNoLandscapePresentationBranch() {
        val source = sourceText("src/mobile/java/app/ownplay/player/ui/TargetLiveRoute.kt")

        assertFalse(source.contains("Configuration.ORIENTATION_LANDSCAPE"))
        assertFalse(source.contains("LocalConfiguration"))
        assertFalse(source.contains("isLandscape"))
    }

    @Test
    fun mobilePrimaryNavigationMatchesDurableMediaDestinations() {
        val source = sourceText("src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt")
        val normalized = normalizedSource(source)
        val nav = normalizedSource(
            sourceBlockAfter(source, "private fun MobilePrimaryNavigationBar("),
        )

        assertTrue(
            "Home must be the default Mobile landing destination",
            normalized.contains("null -> MobileSection.HOME"),
        )
        val homeIndex = nav.indexOf("Text(\"Home\"")
        val liveIndex = nav.indexOf("Text(\"Live\"")
        val libraryIndex = nav.indexOf("Text(\"Library\"")
        val downloadsIndex = nav.indexOf("Text(\"Downloads\"")
        assertTrue(homeIndex >= 0)
        assertTrue(liveIndex > homeIndex)
        assertTrue(libraryIndex > liveIndex)
        assertTrue(downloadsIndex > libraryIndex)
        assertFalse("Settings must not be primary navigation", nav.contains("Text(\"Settings\""))
        assertTrue(
            "Settings must remain available outside primary navigation",
            normalized.contains("MobileAppHeader(onOpenSettings = ::openSettings)"),
        )
    }

    @Test
    fun homeIsContinueWatchingFirstAndDownloadsArePrimary() {
        val shell = normalizedSource(
            sourceText("src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt"),
        )
        val home = normalizedSource(
            sourceText("src/mobile/java/app/ownplay/player/ui/MobileHomeScreen.kt"),
        )
        val settings = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/ui/SettingsScreen.kt"),
        )
        val settingsMenu = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/ui/SettingsInterface.kt"),
        )

        assertTrue(shell.contains("MobileSection.DOWNLOADS -> DownloadsSettingsScreen()"))
        assertTrue(home.contains("vodCatalog.continueWatching"))
        assertTrue(home.contains("seriesCatalog.continueWatching"))
        assertTrue(home.contains("LibraryMovieContinueWatchingStrip("))
        assertTrue(home.contains("LibrarySeriesContinueWatchingStrip("))
        assertFalse(settings.contains("SettingsDestination.DOWNLOADS"))
        assertFalse(settingsMenu.contains("Open downloads"))
        assertFalse(settingsMenu.contains("onOpenDownloads"))
    }

    @Test
    fun mobileBackHierarchyFallsThroughToExitOnlyAtHomeRoot() {
        listOf(
            "src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt" to "MobileSection",
        ).forEach { (path, sectionType) ->
            val source = sourceText(path)
            assertTrue("$path must install a Compose back handler", source.contains("import androidx.activity.compose.BackHandler"))

            val block = normalizedSource(
                sourceBlockAfter(
                    source,
                    "BackHandler(enabled = section != $sectionType.HOME)",
                ),
            )

            assertTrue("$path must give detail/playback back actions priority", block.contains("PlaybackInteractionBridge.handleBack()"))
            assertTrue(
                "$path must return Movies/Series catalog roots to Library",
                block.contains("$sectionType.MOVIES, $sectionType.SERIES, -> openSection($sectionType.LIBRARY)"),
            )
            assertTrue(
                "$path must return Settings to the section that opened it",
                block.contains("$sectionType.SETTINGS -> openSection(settingsReturnSection)"),
            )
            assertTrue(
                "$path must return primary media roots to Home",
                block.contains("$sectionType.LIVE, $sectionType.LIBRARY, $sectionType.DOWNLOADS, -> openSection($sectionType.HOME)"),
            )
            assertFalse("$path shell fallback must never show exit itself", block.contains("showExitConfirmation"))
        }
    }

    private companion object {
        val activeShellPaths = listOf(
            "src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt",
        )
    }
}
