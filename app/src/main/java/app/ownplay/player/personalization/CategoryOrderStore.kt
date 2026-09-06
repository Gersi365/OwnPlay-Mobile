package app.ownplay.player.personalization

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

enum class CategoryOrderFailureReason {
    INVALID_SOURCE_ID,
    INVALID_CATEGORY_ORDER,
    PERSISTENCE_FAILURE,
}

sealed interface CategoryOrderMutationResult {
    data class Success(
        val orderedCategoryKeys: List<String>,
    ) : CategoryOrderMutationResult

    data class Failure(
        val reason: CategoryOrderFailureReason,
    ) : CategoryOrderMutationResult
}

object CategoryOrderPolicy {
    fun normalize(
        savedOrder: List<String>,
        availableCategoryKeys: List<String>,
    ): List<String> {
        val available = availableCategoryKeys
            .asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        val availableSet = available.toSet()
        val retained = savedOrder
            .asSequence()
            .filter { key -> key in availableSet }
            .distinct()
            .toList()
        val retainedSet = retained.toSet()
        return retained + available.filterNot(retainedSet::contains)
    }
}

class CategoryOrderStore(
    context: Context,
    scope: CoroutineScope,
) {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = {
            context.applicationContext.preferencesDataStoreFile(FILE_NAME)
        },
    )

    fun observeOrder(sourceId: String): Flow<List<String>> {
        if (sourceId.isBlank()) return flowOf(emptyList())
        val preferenceKey = preferenceKey(sourceId)
        return dataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }
            .map { preferences -> decode(preferences[preferenceKey].orEmpty()) }
    }

    suspend fun setOrder(
        sourceId: String,
        orderedCategoryKeys: List<String>,
    ): CategoryOrderMutationResult {
        if (sourceId.isBlank()) {
            return CategoryOrderMutationResult.Failure(
                CategoryOrderFailureReason.INVALID_SOURCE_ID,
            )
        }
        val normalized = orderedCategoryKeys
            .map(String::trim)
            .filter(String::isNotBlank)
        if (normalized.size != orderedCategoryKeys.size || normalized.distinct().size != normalized.size) {
            return CategoryOrderMutationResult.Failure(
                CategoryOrderFailureReason.INVALID_CATEGORY_ORDER,
            )
        }

        return try {
            val preferenceKey = preferenceKey(sourceId)
            dataStore.edit { preferences ->
                if (normalized.isEmpty()) {
                    preferences.remove(preferenceKey)
                } else {
                    preferences[preferenceKey] = encode(normalized)
                }
            }
            CategoryOrderMutationResult.Success(normalized)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CategoryOrderMutationResult.Failure(
                CategoryOrderFailureReason.PERSISTENCE_FAILURE,
            )
        }
    }

    suspend fun clearSource(sourceId: String) {
        if (sourceId.isBlank()) return
        dataStore.edit { preferences -> preferences.remove(preferenceKey(sourceId)) }
    }

    private fun preferenceKey(sourceId: String): Preferences.Key<Set<String>> =
        stringSetPreferencesKey("category_order.$sourceId")

    private fun encode(order: List<String>): Set<String> = order
        .mapIndexedTo(linkedSetOf()) { index, key -> "$index:$key" }

    private fun decode(encoded: Set<String>): List<String> = encoded
        .mapNotNull { entry ->
            val separator = entry.indexOf(':')
            if (separator <= 0 || separator == entry.lastIndex) return@mapNotNull null
            val index = entry.substring(0, separator).toIntOrNull() ?: return@mapNotNull null
            index to entry.substring(separator + 1)
        }
        .sortedBy(Pair<Int, String>::first)
        .map(Pair<Int, String>::second)
        .distinct()

    companion object {
        private const val FILE_NAME = "ownplay_category_order.preferences_pb"
    }
}
