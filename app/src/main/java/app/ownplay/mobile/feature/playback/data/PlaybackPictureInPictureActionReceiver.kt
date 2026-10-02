package app.ownplay.mobile.feature.playback.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.feature.playback.domain.PlaybackPictureInPictureActionPolicy

class PlaybackPictureInPictureActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE_PLAY_PAUSE) return
        val application = context.applicationContext as? OwnPlayApplication ?: return
        val controller = application.services.playbackSessionController
        val state = controller.state.value
        if (!PlaybackPictureInPictureActionPolicy.showsPlayPause(state)) return
        if (state.playWhenReady) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    companion object {
        const val ACTION_TOGGLE_PLAY_PAUSE =
            "app.ownplay.mobile.action.PIP_TOGGLE_PLAY_PAUSE"
    }
}
