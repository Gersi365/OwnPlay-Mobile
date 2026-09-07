from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match in {path}, found {count}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1))


manifest = Path("app/src/mobile/AndroidManifest.xml")
replace_once(
    manifest,
    'android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"',
    'android:configChanges="screenSize|smallestScreenSize|screenLayout"',
)
replace_once(
    manifest,
    'android:screenOrientation="unspecified"',
    'android:screenOrientation="portrait"',
)

live = Path("app/src/mobile/java/app/ownplay/player/ui/TargetLiveRoute.kt")
replace_once(
    live,
    '''    LaunchedEffect(state.categories, state.query.categoryKey, preview?.categoryKey) {
        val categories = state.categories
        if (categories.isEmpty()) return@LaunchedEffect
        val selected = state.query.categoryKey
        val target = selected?.takeIf { categoryKey ->
            categories.any { category -> category.providerCategoryKey == categoryKey }
        } ?: preview?.categoryKey?.takeIf { categoryKey ->
            categories.any { category -> category.providerCategoryKey == categoryKey }
        } ?: categories.first().providerCategoryKey
        if (selected != target) {
            browseSession.selectCategory(target)
        }
    }

''',
    '',
)
replace_once(
    live,
    '''        val currentIndex = state.categories.indexOfFirst { category ->
            category.providerCategoryKey == state.query.categoryKey
        }.takeIf { it >= 0 } ?: 0
''',
    '''        val currentIndex = state.categories.indexOfFirst { category ->
            category.providerCategoryKey == state.query.categoryKey
        }.takeIf { it >= 0 } ?: -1
''',
)
replace_once(
    live,
    '                            onClick = { onCategorySelected(category.providerCategoryKey) },\n',
    '''                            onClick = {
                                onCategorySelected(
                                    if (state.query.categoryKey == category.providerCategoryKey) {
                                        null
                                    } else {
                                        category.providerCategoryKey
                                    },
                                )
                            },
''',
)

library = Path("app/src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt")
text = library.read_text()
if "UnifiedLibraryFilter.ALL" not in text:
    raise SystemExit("Expected legacy UnifiedLibraryFilter.ALL references")
text = text.replace("UnifiedLibraryFilter.ALL", "UnifiedLibraryFilter.OFFLINE")
text = text.replace("    ALL,\n", "    OFFLINE,\n", 1)
text = text.replace("import app.ownplay.player.ui.view.ContentViewModeMenu\n", "")
text = text.replace("import app.ownplay.player.ui.view.ContentViewModeStore\n", "")
legacy_store = '''    val viewModeStore = remember(context) {
        ContentViewModeStore(context.applicationContext)
    }
'''
if text.count(legacy_store) != 1:
    raise SystemExit("Expected one ContentViewModeStore initialization")
text = text.replace(legacy_store, "", 1)
legacy_mode = "    val libraryViewMode by viewModeStore.libraryMode.collectAsState(initial = ContentViewMode.CARDS)\n"
if text.count(legacy_mode) != 1:
    raise SystemExit("Expected one libraryViewMode collection")
text = text.replace(legacy_mode, "", 1)
legacy_menu = '''                item(key = "library-view") {
                    ContentViewModeMenu(
                        mode = libraryViewMode,
                        onModeSelected = { mode ->
                            scope.launch { viewModeStore.setLibraryMode(mode) }
                        },
                    )
                }
'''
if text.count(legacy_menu) != 1:
    raise SystemExit("Expected one legacy library view-mode menu")
text = text.replace(legacy_menu, "", 1)
if text.count("viewMode = libraryViewMode,") != 1:
    raise SystemExit("Expected one LibraryCatalogView libraryViewMode argument")
text = text.replace("viewMode = libraryViewMode,", "viewMode = ContentViewMode.CARDS,", 1)
library.write_text(text)

test = Path("app/src/test/java/app/ownplay/player/PortraitUiSimplificationContractTest.kt")
if test.exists():
    raise SystemExit(f"Test already exists: {test}")
test.write_text('''package app.ownplay.player

import app.ownplay.player.testing.normalizedSource
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitUiSimplificationContractTest {
    @Test
    fun `Mobile activity is portrait only`() {
        val manifest = sourceText("src/mobile/AndroidManifest.xml")

        assertTrue(manifest.contains("android:screenOrientation=\\\"portrait\\\""))
        assertFalse(manifest.contains("android:screenOrientation=\\\"unspecified\\\""))
        assertFalse(manifest.contains("screenLayout|orientation"))
    }

    @Test
    fun `Live uses default unfiltered set without All control`() {
        val live = normalizedSource(
            sourceText("src/mobile/java/app/ownplay/player/ui/TargetLiveRoute.kt"),
        )

        assertFalse(live.contains("categories.first().providerCategoryKey"))
        assertFalse(live.contains("Text(\\\"All\\\")"))
        assertTrue(
            live.contains(
                "if (state.query.categoryKey == category.providerCategoryKey) { null } else { category.providerCategoryKey }",
            ),
        )
    }

    @Test
    fun `Library has Offline filter and no active view-mode selector`() {
        val library = sourceText(
            "src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt",
        )

        assertFalse(library.contains("UnifiedLibraryFilter.ALL"))
        assertTrue(library.contains("UnifiedLibraryFilter.OFFLINE"))
        assertFalse(library.contains("ContentViewModeMenu"))
        assertFalse(library.contains("ContentViewModeStore"))
        assertFalse(library.contains("libraryViewMode"))
        assertTrue(library.contains("viewMode = ContentViewMode.CARDS"))
    }

    @Test
    fun `Settings exposes no Interface destination`() {
        val settings = sourceText("src/main/java/app/ownplay/player/ui/SettingsScreen.kt") +
            sourceText("src/main/java/app/ownplay/player/ui/SettingsPortrait.kt")

        assertFalse(settings.contains("INTERFACE"))
        assertFalse(settings.contains("\\\"Interface\\\""))
    }
}
''')
