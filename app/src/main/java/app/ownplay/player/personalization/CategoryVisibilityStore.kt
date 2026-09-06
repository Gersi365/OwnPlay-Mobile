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

enum class CategoryVisibilityFailureReason {
    INVALID_SOURCE_ID,
    EMPTY_CATEGORY_KEY,
    CATEGORY_NOT_FOUND,
    PERSISTENCE_FAILURE,
}

sealed interface CategoryVisibilityMutationResult {
    data class Success(
        val providerCategoryKey: String,
        val hidden: Boolean,
    ) : CategoryVisibilityMutationResult

    data class Failure(
        val reason: CategoryVisibilityFailureReason,
    ) : CategoryVisibilityMutationResult
}

class CategoryVisibilityStore(
    context: Context,
    scope: CoroutineScope,
) {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = {
            context.applicationContext.preferencesDataStoreFile(FILE_NAME)
        },
    )

    fun observeHiddenCategoryKeys(sourceId: String): Flow<Set<String>> {
        if (sourceId.isBlank()) return flowOf(emptySet())
        val preferenceKey = preferenceKey(sourceId)
        return dataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }
            .map { preferences -> preferences[preferenceKey].orEmpty().toSet() }
    }

    suspend fun setHidden(
        sourceId: String,
        providerCategoryKey: String,
        hidden: Boolean,
    ): CategoryVisibilityMutationResult {
        if (sourceId.isBlank()) {
            return CategoryVisibilityMutationResult.Failure(
                CategoryVisibilityFailureReason.INVALID_SOURCE_ID,
            )
        }
        if (providerCategoryKey.isBlank()) {
            return CategoryVisibilityMutationResult.Failure(
                CategoryVisibilityFailureReason.EMPTY_CATEGORY_KEY,
            )
        }

        return try {
            val preferenceKey = preferenceKey(sourceId)
            dataStore.edit { preferences ->
                val next = preferences[preferenceKey].orEmpty().toMutableSet()
                if (hidden) {
                    next += providerCategoryKey
                } else {
                    next -= providerCategoryKey
                }
                if (next.isEmpty()) {
                    preferences.remove(preferenceKey)
                } else {
                    preferences[preferenceKey] = next
                }
            }
            CategoryVisibilityMutationResult.Success(
                providerCategoryKey = providerCategoryKey,
                hidden = hidden,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CategoryVisibilityMutationResult.Failure(
                CategoryVisibilityFailureReason.PERSISTENCE_FAILURE,
            )
        }
    }

    suspend fun clearSource(sourceId: String) {
        if (sourceId.isBlank()) return
        dataStore.edit { preferences -> preferences.remove(preferenceKey(sourceId)) }
    }

    private fun preferenceKey(sourceId: String): Preferences.Key<Set<String>> =
        stringSetPreferencesKey("hidden_categories.$sourceId")

    companion object {
        private const val FILE_NAME = "ownplay_category_visibility.preferences_pb"
    }
}

class CategoryVisibilityMutator(
    private val store: CategoryVisibilityStore,
    private val categoryExists: suspend (sourceId: String, providerCategoryKey: String) -> Boolean,
) {
    suspend fun hide(
        sourceId: String,
        providerCategoryKey: String,
    ): CategoryVisibilityMutationResult = mutate(
        sourceId = sourceId,
        providerCategoryKey = providerCategoryKey,
        hidden = true,
    )

    suspend fun unhide(
        sourceId: String,
        providerCategoryKey: String,
    ): CategoryVisibilityMutationResult = mutate(
        sourceId = sourceId,
        providerCategoryKey = providerCategoryKey,
        hidden = false,
    )

    private suspend fun mutate(
        sourceId: String,
        providerCategoryKey: String,
        hidden: Boolean,
    ): CategoryVisibilityMutationResult {
        if (sourceId.isBlank()) {
            return CategoryVisibilityMutationResult.Failure(
                CategoryVisibilityFailureReason.INVALID_SOURCE_ID,
            )
        }
        if (providerCategoryKey.isBlank()) {
            return CategoryVisibilityMutationResult.Failure(
                CategoryVisibilityFailureReason.EMPTY_CATEGORY_KEY,
            )
        }

        val exists = try {
            categoryExists(sourceId, providerCategoryKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CategoryVisibilityMutationResult.Failure(
                CategoryVisibilityFailureReason.PERSISTENCE_FAILURE,
            )
        }
        if (!exists) {
            return CategoryVisibilityMutationResult.Failure(
                CategoryVisibilityFailureReason.CATEGORY_NOT_FOUND,
            )
        }

        return store.setHidden(
            sourceId = sourceId,
            providerCategoryKey = providerCategoryKey,
            hidden = hidden,
        )
    }
}
