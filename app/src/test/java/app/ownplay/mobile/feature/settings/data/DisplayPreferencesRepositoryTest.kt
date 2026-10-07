package app.ownplay.mobile.feature.settings.data

import app.ownplay.mobile.feature.settings.domain.DisplayPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayPreferencesRepositoryTest {
    @Test
    fun displayPreferencesPersistThroughRepository() = runBlocking {
        val store = FakeDisplayPreferencesStore()
        val repository = DataStoreDisplayPreferencesRepository(store)

        assertTrue(repository.setCompactMediaRows(true))
        assertTrue(repository.setShowChannelLogos(false))
        assertTrue(repository.setPreferTvgName(true))
        assertTrue(repository.setHideChannelPrefix(true))
        assertTrue(repository.setShowCategoryFlags(false))
        assertTrue(repository.setHideLiveCategoryPrefix(true))
        assertTrue(repository.setHideLibraryCategoryPrefix(true))
        val current = repository.preferences.first()
        assertTrue(current.compactMediaRows)
        assertFalse(current.showChannelLogos)
        assertTrue(current.preferTvgName)
        assertTrue(current.hideChannelPrefix)
        assertFalse(current.showCategoryFlags)
        assertTrue(current.hideLiveCategoryPrefix)
        assertTrue(current.hideLibraryCategoryPrefix)
    }

    @Test
    fun storageFailureIsReportedWithoutInventingSuccess() = runBlocking {
        val store = FakeDisplayPreferencesStore(failWrites = true)
        val repository = DataStoreDisplayPreferencesRepository(store)

        assertFalse(repository.setCompactMediaRows(true))
        assertFalse(repository.setShowChannelLogos(false))
        assertFalse(repository.setPreferTvgName(true))
        assertFalse(repository.setHideChannelPrefix(true))
        assertFalse(repository.setShowCategoryFlags(false))
        assertFalse(repository.setHideLiveCategoryPrefix(true))
        assertFalse(repository.setHideLibraryCategoryPrefix(true))
        assertFalse(repository.preferences.first().compactMediaRows)
        assertTrue(repository.preferences.first().showChannelLogos)
        assertFalse(repository.preferences.first().preferTvgName)
        assertTrue(repository.preferences.first().hideChannelPrefix)
        assertTrue(repository.preferences.first().showCategoryFlags)
        assertFalse(repository.preferences.first().hideLiveCategoryPrefix)
        assertFalse(repository.preferences.first().hideLibraryCategoryPrefix)
    }
}

private class FakeDisplayPreferencesStore(
    private val failWrites: Boolean = false,
) : DisplayPreferencesStore {
    private val state = MutableStateFlow(DisplayPreferences())
    override val preferences: Flow<DisplayPreferences> = state

    override suspend fun setCompactMediaRows(enabled: Boolean) = update { copy(compactMediaRows = enabled) }
    override suspend fun setShowChannelLogos(enabled: Boolean) = update { copy(showChannelLogos = enabled) }
    override suspend fun setPreferTvgName(enabled: Boolean) = update { copy(preferTvgName = enabled) }
    override suspend fun setHideChannelPrefix(enabled: Boolean) = update { copy(hideChannelPrefix = enabled) }
    override suspend fun setShowCategoryFlags(enabled: Boolean) = update { copy(showCategoryFlags = enabled) }
    override suspend fun setHideLiveCategoryPrefix(enabled: Boolean) = update { copy(hideLiveCategoryPrefix = enabled) }
    override suspend fun setHideLibraryCategoryPrefix(enabled: Boolean) = update { copy(hideLibraryCategoryPrefix = enabled) }

    private fun update(block: DisplayPreferences.() -> DisplayPreferences) {
        if (failWrites) error("storage failed")
        state.value = state.value.block()
    }
}
