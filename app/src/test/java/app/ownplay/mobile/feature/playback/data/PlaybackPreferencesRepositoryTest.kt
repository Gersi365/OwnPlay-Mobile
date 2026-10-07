package app.ownplay.mobile.feature.playback.data

import app.ownplay.mobile.feature.playback.domain.PlaybackPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPreferencesRepositoryTest {
    @Test
    fun playbackPreferencesPersistThroughRepository() = runBlocking {
        val store = FakePlaybackPreferencesStore()
        val repository = DataStorePlaybackPreferencesRepository(store)

        assertTrue(repository.setAutomaticPictureInPicture(false))
        assertTrue(repository.setPlayerVolume(0.45f))
        val current = repository.preferences.first()
        assertFalse(current.automaticPictureInPicture)
        assertEquals(0.45f, current.playerVolume)
    }

    @Test
    fun invalidVolumeAndStorageFailureDoNotReportSuccess() = runBlocking {
        val store = FakePlaybackPreferencesStore(failWrites = true)
        val repository = DataStorePlaybackPreferencesRepository(store)

        assertFalse(repository.setAutomaticPictureInPicture(false))
        assertFalse(repository.setPlayerVolume(0.4f))
        assertFalse(repository.setPlayerVolume(1.2f))
        val current = repository.preferences.first()
        assertTrue(current.automaticPictureInPicture)
        assertEquals(1f, current.playerVolume)
    }
}

private class FakePlaybackPreferencesStore(
    private val failWrites: Boolean = false,
) : PlaybackPreferencesStore {
    private val state = MutableStateFlow(PlaybackPreferences())
    override val preferences: Flow<PlaybackPreferences> = state

    override suspend fun setAutomaticPictureInPicture(enabled: Boolean) =
        update { copy(automaticPictureInPicture = enabled) }

    override suspend fun setPlayerVolume(volume: Float) =
        update { copy(playerVolume = volume) }

    private fun update(block: PlaybackPreferences.() -> PlaybackPreferences) {
        if (failWrites) error("storage failed")
        state.value = state.value.block()
    }
}
