package app.ownplay.mobile.feature.playback.domain

import kotlinx.coroutines.flow.Flow

data class PlaybackPreferences(
    val automaticPictureInPicture: Boolean = true,
    val playerVolume: Float = 1f,
) {
    init {
        require(playerVolume in 0f..1f) { "Player volume must be between 0 and 1" }
    }
}

interface PlaybackPreferencesRepository {
    val preferences: Flow<PlaybackPreferences>

    suspend fun setAutomaticPictureInPicture(enabled: Boolean): Boolean

    suspend fun setPlayerVolume(volume: Float): Boolean
}

object PlaybackAutomaticPictureInPicturePolicy {
    fun isEligible(
        preferences: PlaybackPreferences,
        state: PlaybackSessionState,
    ): Boolean =
        preferences.automaticPictureInPicture && PlaybackPictureInPicturePolicy.isEligible(state)
}
