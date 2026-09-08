package app.ownplay.player

import app.ownplay.player.testing.normalizedSource
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitUiSimplificationContractTest {
    @Test
    fun `Mobile activity is portrait only`() {
        val manifest = sourceText("src/mobile/AndroidManifest.xml")

        assertTrue(manifest.contains("android:screenOrientation=\"portrait\""))
        assertFalse(manifest.contains("android:screenOrientation=\"unspecified\""))
        assertFalse(manifest.contains("screenLayout|orientation"))
    }

    @Test
    fun `Live uses default unfiltered set without All control`() {
        val live = normalizedSource(
            sourceText("src/mobile/java/app/ownplay/player/ui/TargetLiveRoute.kt"),
        )

        assertFalse(live.contains("categories.first().providerCategoryKey"))
        assertFalse(live.contains("Text(\"All\")"))
        assertTrue(
            live.contains(
                "if (state.query.categoryKey == category.providerCategoryKey) { null } else { category.providerCategoryKey }",
            ),
        )
    }

    @Test
    fun `Library keeps Movies and Series without duplicating Downloads management`() {
        val library = sourceText(
            "src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt",
        )

        assertFalse(library.contains("UnifiedLibraryFilter.ALL"))
        assertTrue(library.contains("UnifiedLibraryFilter.MOVIES"))
        assertTrue(library.contains("UnifiedLibraryFilter.SERIES"))
        assertFalse(library.contains("UnifiedLibraryFilter.OFFLINE"))
        assertFalse(library.contains("ContentViewModeMenu"))
        assertFalse(library.contains("ContentViewModeStore"))
        assertFalse(library.contains("libraryViewMode"))
        assertFalse(library.contains("ContentViewMode"))
    }

    @Test
    fun `Settings exposes no Interface destination`() {
        val settings = sourceText("src/main/java/app/ownplay/player/ui/SettingsScreen.kt") +
            sourceText("src/main/java/app/ownplay/player/ui/SettingsPortrait.kt")

        assertFalse(settings.contains("INTERFACE"))
        assertFalse(settings.contains("\"Interface\""))
    }
}
