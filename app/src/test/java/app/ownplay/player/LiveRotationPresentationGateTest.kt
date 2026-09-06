package app.ownplay.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRotationPresentationGateTest {
    @Test
    fun `smartphone live rotation is enabled outside picture in picture`() {
        assertTrue(
            liveRotationFullscreenEnabled(
                isSmartphone = true,
                inPictureInPicture = false,
            ),
        )
    }

    @Test
    fun `picture in picture blocks smartphone live rotation presentation changes`() {
        assertFalse(
            liveRotationFullscreenEnabled(
                isSmartphone = true,
                inPictureInPicture = true,
            ),
        )
    }

    @Test
    fun `non smartphone profiles never enable live rotation fullscreen`() {
        assertFalse(
            liveRotationFullscreenEnabled(
                isSmartphone = false,
                inPictureInPicture = false,
            ),
        )
        assertFalse(
            liveRotationFullscreenEnabled(
                isSmartphone = false,
                inPictureInPicture = true,
            ),
        )
    }
}
