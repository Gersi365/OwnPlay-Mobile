package app.ownplay.player.ui.view

import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Legacy renderer keys retained only so older compiled presentation branches remain source-compatible.
 * OwnPlay Mobile exposes one canonical browsing presentation and never restores a persisted alternate
 * view mode.
 */
enum class ContentViewMode {
    LIST,
    COMPACT,
    CARDS,
}

internal fun canonicalContentViewMode(
    @Suppress("UNUSED_PARAMETER") requested: ContentViewMode? = null,
): ContentViewMode = ContentViewMode.CARDS

/**
 * Compatibility boundary for installations that may still contain historical view-mode preferences.
 * Those values are intentionally ignored: Mobile now has one canonical Cards presentation.
 */
@Suppress("UNUSED_PARAMETER")
class ContentViewModeStore(context: Context) {
    val liveMode: Flow<ContentViewMode> = flowOf(canonicalContentViewMode())
    val libraryMode: Flow<ContentViewMode> = flowOf(canonicalContentViewMode())

    suspend fun setLiveMode(mode: ContentViewMode) = Unit

    suspend fun setLibraryMode(mode: ContentViewMode) = Unit
}

/** Alternate view selection is intentionally not part of the OwnPlay Mobile UI. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ContentViewModeMenu(
    mode: ContentViewMode,
    onModeSelected: (ContentViewMode) -> Unit,
) = Unit
