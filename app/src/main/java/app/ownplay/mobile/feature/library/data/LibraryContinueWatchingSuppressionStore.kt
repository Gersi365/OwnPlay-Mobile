package app.ownplay.mobile.feature.library.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.ownplay.mobile.feature.library.domain.LibraryContentKind
import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.libraryContinueWatchingDataStore by preferencesDataStore(
    name = "ownplay_library_continue_watching",
)

internal data class LibraryContinueWatchingSuppressionKey(
    val contentKind: LibraryContentKind,
    val contentId: String,
)

internal interface LibraryContinueWatchingSuppressionStore {
    fun observe(sourceId: SourceId): Flow<Map<LibraryContinueWatchingSuppressionKey, Long>>

    suspend fun suppress(
        sourceId: SourceId,
        contentKind: LibraryContentKind,
        contentId: String,
        suppressedAt: Long,
    )
}

internal class DataStoreLibraryContinueWatchingSuppressionStore(
    private val context: Context,
) : LibraryContinueWatchingSuppressionStore {
    override fun observe(
        sourceId: SourceId,
    ): Flow<Map<LibraryContinueWatchingSuppressionKey, Long>> =
        context.libraryContinueWatchingDataStore.data.map { preferences ->
            preferences[key(sourceId)]
                .orEmpty()
                .mapNotNull(::decode)
                .associate { it.first to it.second }
        }

    override suspend fun suppress(
        sourceId: SourceId,
        contentKind: LibraryContentKind,
        contentId: String,
        suppressedAt: Long,
    ) {
        require(contentId.isNotBlank())
        context.libraryContinueWatchingDataStore.edit { preferences ->
            val preferenceKey = key(sourceId)
            val existing = preferences[preferenceKey].orEmpty()
                .mapNotNull(::decode)
                .associate { it.first to it.second }
                .toMutableMap()
            existing[LibraryContinueWatchingSuppressionKey(contentKind, contentId)] = suppressedAt
            preferences[preferenceKey] = existing.mapTo(mutableSetOf()) { (identity, timestamp) ->
                encode(identity, timestamp)
            }
        }
    }

    private fun key(sourceId: SourceId) =
        stringSetPreferencesKey("continue_watching_suppression_${sourceId.value}")

    private fun encode(
        key: LibraryContinueWatchingSuppressionKey,
        timestamp: Long,
    ): String = "${key.contentKind.name}\t${key.contentId}\t$timestamp"

    private fun decode(
        value: String,
    ): Pair<LibraryContinueWatchingSuppressionKey, Long>? {
        val first = value.indexOf('\t')
        val last = value.lastIndexOf('\t')
        if (first <= 0 || last <= first) return null
        val kind = runCatching {
            LibraryContentKind.valueOf(value.substring(0, first))
        }.getOrNull() ?: return null
        val contentId = value.substring(first + 1, last).takeIf(String::isNotBlank) ?: return null
        val timestamp = value.substring(last + 1).toLongOrNull() ?: return null
        return LibraryContinueWatchingSuppressionKey(kind, contentId) to timestamp
    }
}
