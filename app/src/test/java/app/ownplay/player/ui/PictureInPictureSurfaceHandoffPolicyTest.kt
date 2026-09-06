package app.ownplay.player.ui

import app.ownplay.player.playback.PlaybackMediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PictureInPictureSurfaceHandoffPolicyTest {
    @Test
    fun `live preview uses Media3 transfer for PiP handoff`() {
        assertEquals(
            PictureInPictureSurfaceBindingMode.MEDIA3_TRANSFER,
            PictureInPictureSurfaceHandoffPolicy.modeFor(
                mediaKind = PlaybackMediaKind.LIVE,
                liveWasFullscreen = false,
            ),
        )

        val events = mutableListOf<String>()
        PictureInPictureSurfaceHandoffPolicy.handoff(
            mode = PictureInPictureSurfaceBindingMode.MEDIA3_TRANSFER,
            detachCurrentSurface = { events += "detach" },
            bindDestinationSurface = { events += "bind" },
        )

        assertEquals(listOf("bind"), events)
    }

    @Test
    fun `live fullscreen retains detach before PiP destination bind`() {
        assertEquals(
            PictureInPictureSurfaceBindingMode.DETACH_BEFORE_BIND,
            PictureInPictureSurfaceHandoffPolicy.modeFor(
                mediaKind = PlaybackMediaKind.LIVE,
                liveWasFullscreen = true,
            ),
        )

        val events = mutableListOf<String>()
        PictureInPictureSurfaceHandoffPolicy.handoff(
            mode = PictureInPictureSurfaceBindingMode.DETACH_BEFORE_BIND,
            detachCurrentSurface = { events += "detach" },
            bindDestinationSurface = { events += "bind" },
        )

        assertEquals(listOf("detach", "bind"), events)
    }

    @Test
    fun `movie and series retain existing Media3 transfer path`() {
        listOf(
            PlaybackMediaKind.MOVIE,
            PlaybackMediaKind.SERIES_EPISODE,
            null,
        ).forEach { mediaKind ->
            assertEquals(
                PictureInPictureSurfaceBindingMode.MEDIA3_TRANSFER,
                PictureInPictureSurfaceHandoffPolicy.modeFor(mediaKind),
            )
        }

        val events = mutableListOf<String>()
        PictureInPictureSurfaceHandoffPolicy.handoff(
            mode = PictureInPictureSurfaceBindingMode.MEDIA3_TRANSFER,
            detachCurrentSurface = { events += "detach" },
            bindDestinationSurface = { events += "bind" },
        )

        assertEquals(listOf("bind"), events)
    }
}
