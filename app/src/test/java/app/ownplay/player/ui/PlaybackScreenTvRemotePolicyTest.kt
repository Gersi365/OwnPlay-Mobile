package app.ownplay.player.ui

import android.view.KeyEvent
import app.ownplay.player.playback.PlaybackNavigationDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackScreenTvRemotePolicyTest {
    @Test
    fun `channel up maps to next live channel`() {
        assertEquals(
            PlaybackNavigationDirection.NEXT,
            tvLiveChannelNavigationForKeyCode(KeyEvent.KEYCODE_CHANNEL_UP),
        )
    }

    @Test
    fun `channel down maps to previous live channel`() {
        assertEquals(
            PlaybackNavigationDirection.PREVIOUS,
            tvLiveChannelNavigationForKeyCode(KeyEvent.KEYCODE_CHANNEL_DOWN),
        )
    }

    @Test
    fun `dpad up and down are not direct live channel navigation`() {
        assertNull(tvLiveChannelNavigationForKeyCode(KeyEvent.KEYCODE_DPAD_UP))
        assertNull(tvLiveChannelNavigationForKeyCode(KeyEvent.KEYCODE_DPAD_DOWN))
    }
}
