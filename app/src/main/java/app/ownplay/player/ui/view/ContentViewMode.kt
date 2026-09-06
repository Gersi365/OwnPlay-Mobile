package app.ownplay.player.ui.view

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.ownplay.player.ui.tv.TvPopupFocusAction
import app.ownplay.player.ui.tv.TvPopupFocusPolicy
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

internal enum class ContentViewMode(
    val storageValue: String,
    val label: String,
) {
    LIST("list", "List"),
    COMPACT("compact", "Compact"),
    CARDS("cards", "Gallery"),
    ;

    companion object {
        fun fromStorageValue(
            value: String?,
            default: ContentViewMode,
        ): ContentViewMode = entries.firstOrNull { it.storageValue == value } ?: default
    }
}

internal enum class ContentViewSurface {
    LIVE,
    LIBRARY,
}

private val Context.contentViewModeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "content_view_modes",
)

internal class ContentViewModeStore(context: Context) {
    private val appContext = context.applicationContext

    val liveMode: Flow<ContentViewMode> = modeFlow(
        key = LIVE_VIEW_MODE,
        default = ContentViewMode.COMPACT,
    )

    val libraryMode: Flow<ContentViewMode> = modeFlow(
        key = LIBRARY_VIEW_MODE,
        default = ContentViewMode.CARDS,
    )

    suspend fun setLiveMode(mode: ContentViewMode) {
        setMode(LIVE_VIEW_MODE, mode)
    }

    suspend fun setLibraryMode(mode: ContentViewMode) {
        setMode(LIBRARY_VIEW_MODE, mode)
    }

    private fun modeFlow(
        key: Preferences.Key<String>,
        default: ContentViewMode,
    ): Flow<ContentViewMode> = appContext.contentViewModeDataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            ContentViewMode.fromStorageValue(preferences[key], default)
        }

    private suspend fun setMode(
        key: Preferences.Key<String>,
        mode: ContentViewMode,
    ) {
        appContext.contentViewModeDataStore.edit { preferences ->
            preferences[key] = mode.storageValue
        }
    }

    private companion object {
        val LIVE_VIEW_MODE = stringPreferencesKey("live_view_mode")
        val LIBRARY_VIEW_MODE = stringPreferencesKey("library_view_mode")
    }
}

@Composable
internal fun ContentViewModeMenu(
    mode: ContentViewMode,
    onModeSelected: (ContentViewMode) -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    tvFocusManagement: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var wasExpanded by remember { mutableStateOf(false) }
    val triggerFocusRequester = remember { FocusRequester() }
    val selectedItemFocusRequester = remember(mode) { FocusRequester() }

    BackHandler(enabled = tvFocusManagement && expanded) {
        expanded = false
    }

    LaunchedEffect(expanded, tvFocusManagement, mode) {
        val focusAction = TvPopupFocusPolicy.action(
            enabled = tvFocusManagement,
            expanded = expanded,
            wasExpanded = wasExpanded,
        )
        wasExpanded = expanded
        when (focusAction) {
            TvPopupFocusAction.FOCUS_SELECTED_ITEM -> {
                withFrameNanos { }
                selectedItemFocusRequester.requestFocus()
            }
            TvPopupFocusAction.RESTORE_TRIGGER -> {
                withFrameNanos { }
                triggerFocusRequester.requestFocus()
            }
            TvPopupFocusAction.NONE -> Unit
        }
    }

    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            modifier = if (tvFocusManagement) {
                Modifier.focusRequester(triggerFocusRequester)
            } else {
                Modifier
            },
        ) {
            Text(
                text = buildString {
                    prefix?.takeIf(String::isNotBlank)?.let {
                        append(it)
                        append(" · ")
                    }
                    append(mode.label)
                },
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Change view",
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ContentViewMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (option == mode) "${option.label} ✓" else option.label,
                        )
                    },
                    onClick = {
                        expanded = false
                        if (option != mode) onModeSelected(option)
                    },
                    modifier = if (tvFocusManagement && option == mode) {
                        Modifier.focusRequester(selectedItemFocusRequester)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}
