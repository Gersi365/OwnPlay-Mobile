package app.ownplay.mobile.feature.playback.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferences
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.rebuildPlaybackPreferencesDataStore by preferencesDataStore(
    name = "ownplay_rebuild_playback_preferences",
)

internal interface PlaybackPreferencesStore {
    val preferences: Flow<PlaybackPreferences>
    suspend fun setAutomaticPictureInPicture(enabled: Boolean)
    suspend fun setPlayerVolume(volume: Float)
}

internal class PlaybackPreferencesDataStore(
    private val context: Context,
) : PlaybackPreferencesStore {
    override val preferences: Flow<PlaybackPreferences> =
        context.rebuildPlaybackPreferencesDataStore.data.map { values ->
            PlaybackPreferences(
                automaticPictureInPicture = values[AUTOMATIC_PIP] ?: true,
                playerVolume = (values[PLAYER_VOLUME] ?: 1f).coerceIn(0f, 1f),
            )
        }

    override suspend fun setAutomaticPictureInPicture(enabled: Boolean) {
        context.rebuildPlaybackPreferencesDataStore.edit { values ->
            values[AUTOMATIC_PIP] = enabled
        }
    }

    override suspend fun setPlayerVolume(volume: Float) {
        context.rebuildPlaybackPreferencesDataStore.edit { values ->
            values[PLAYER_VOLUME] = volume.coerceIn(0f, 1f)
        }
    }

    private companion object {
        val AUTOMATIC_PIP = booleanPreferencesKey("automatic_picture_in_picture")
        val PLAYER_VOLUME = floatPreferencesKey("player_volume")
    }
}

internal class DataStorePlaybackPreferencesRepository(
    private val store: PlaybackPreferencesStore,
) : PlaybackPreferencesRepository {
    override val preferences: Flow<PlaybackPreferences> = store.preferences

    override suspend fun setAutomaticPictureInPicture(enabled: Boolean): Boolean = write {
        store.setAutomaticPictureInPicture(enabled)
    }

    override suspend fun setPlayerVolume(volume: Float): Boolean {
        if (volume !in 0f..1f) return false
        return write { store.setPlayerVolume(volume) }
    }

    private suspend fun write(block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (_: Exception) {
            false
        }
}
