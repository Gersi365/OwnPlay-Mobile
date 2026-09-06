package app.ownplay.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRotationPresentationGateTest {
    @Test
    fun `live rotation is enabled outside picture in picture`() {
        assertTrue(
            liveRotationFullscreenEnabled(
                inPictureInPicture = false,
            ),
        )
    }

    @Test
    fun `picture in picture blocks live rotation presentation changes`() {
        assertFalse(
            liveRotationFullscreenEnabled(
                inPictureInPicture = true,
            ),
        )
    }
}
