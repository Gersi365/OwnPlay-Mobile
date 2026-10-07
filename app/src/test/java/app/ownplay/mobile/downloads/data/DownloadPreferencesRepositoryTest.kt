package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.downloads.domain.DownloadDestinationPolicy
import app.ownplay.mobile.downloads.domain.DownloadPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPreferencesRepositoryTest {
    @Test
    fun preferencesPersistThroughRepository() = runBlocking {
        val store = FakeDownloadPreferencesStore()
        val repository = DataStoreDownloadPreferencesRepository(store)

        assertTrue(repository.setUnmeteredNetworkOnly(true))
        assertTrue(repository.setDestinationRelativePath("Download/OwnPlay Test/"))
        assertTrue(repository.setNotificationsEnabled(false))

        val current = repository.preferences.first()
        assertTrue(current.wifiOnly)
        assertEquals("Download/OwnPlay Test/", current.destinationRelativePath)
        assertFalse(current.notificationsEnabled)
    }

    @Test
    fun invalidDestinationAndStorageFailureDoNotReportSuccess() = runBlocking {
        val store = FakeDownloadPreferencesStore(failWrites = true)
        val repository = DataStoreDownloadPreferencesRepository(store)

        assertFalse(repository.setDestinationRelativePath("../unsafe"))
        assertFalse(repository.setUnmeteredNetworkOnly(true))
        assertFalse(repository.setNotificationsEnabled(false))
        assertEquals(DownloadPreferences(), repository.preferences.first())
    }
}

private class FakeDownloadPreferencesStore(
    private val failWrites: Boolean = false,
) : DownloadPreferencesStore {
    private val state = MutableStateFlow(DownloadPreferences())
    override val preferences: Flow<DownloadPreferences> = state

    override suspend fun setUnmeteredNetworkOnly(enabled: Boolean) =
        update { copy(unmeteredNetworkOnly = enabled) }

    override suspend fun setDestinationRelativePath(relativePath: String) =
        update {
            copy(
                destinationRelativePath = DownloadDestinationPolicy.normalize(relativePath)
                    ?: error("invalid"),
            )
        }

    override suspend fun setNotificationsEnabled(enabled: Boolean) =
        update { copy(notificationsEnabled = enabled) }

    private fun update(block: DownloadPreferences.() -> DownloadPreferences) {
        if (failWrites) error("storage failed")
        state.value = state.value.block()
    }
}
