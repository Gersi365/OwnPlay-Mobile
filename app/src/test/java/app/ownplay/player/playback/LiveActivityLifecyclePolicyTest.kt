package app.ownplay.player.playback

import app.ownplay.player.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveActivityLifecyclePolicyTest {
    @Test
    fun activeLiveSuspendsOutsidePipAndConfigurationChange() {
        val request = request(PlaybackMediaKind.LIVE)
        listOf(
            PlaybackState.Loading(request),
            PlaybackState.Playing(request),
            PlaybackState.Paused(request),
        ).forEach { state ->
            assertEquals(
                LiveActivityBackgroundAction.SUSPEND_AND_RETAIN_SURFACE,
                LiveActivityLifecyclePolicy.backgroundAction(
                    state = state,
                    inPictureInPicture = false,
                    changingConfigurations = false,
                ),
            )
        }
    }

    @Test
    fun liveKeepsRunningWhenPipOwnsPlayback() {
        assertEquals(
            LiveActivityBackgroundAction.NONE,
            LiveActivityLifecyclePolicy.backgroundAction(
                state = PlaybackState.Playing(request(PlaybackMediaKind.LIVE)),
                inPictureInPicture = true,
                changingConfigurations = false,
            ),
        )
    }

    @Test
    fun configurationChangeDoesNotCreateBackgroundSuspension() {
        assertEquals(
            LiveActivityBackgroundAction.NONE,
            LiveActivityLifecyclePolicy.backgroundAction(
                state = PlaybackState.Playing(request(PlaybackMediaKind.LIVE)),
                inPictureInPicture = false,
                changingConfigurations = true,
            ),
        )
    }

    @Test
    fun nonLiveSuspendsOnlyOnMobileOutsidePipAndConfigurationChange() {
        val expected = if (BuildConfig.IS_TV_BUILD) {
            LiveActivityBackgroundAction.NONE
        } else {
            LiveActivityBackgroundAction.SUSPEND_AND_RETAIN_SURFACE
        }

        listOf(PlaybackMediaKind.MOVIE, PlaybackMediaKind.SERIES_EPISODE).forEach { kind ->
            val request = request(kind)
            listOf(
                PlaybackState.Loading(request),
                PlaybackState.Playing(request),
                PlaybackState.Paused(request),
            ).forEach { state ->
                assertEquals(
                    expected,
                    LiveActivityLifecyclePolicy.backgroundAction(
                        state = state,
                        inPictureInPicture = false,
                        changingConfigurations = false,
                    ),
                )
            }

            assertEquals(
                LiveActivityBackgroundAction.NONE,
                LiveActivityLifecyclePolicy.backgroundAction(
                    state = PlaybackState.Playing(request),
                    inPictureInPicture = true,
                    changingConfigurations = false,
                ),
            )
            assertEquals(
                LiveActivityBackgroundAction.NONE,
                LiveActivityLifecyclePolicy.backgroundAction(
                    state = PlaybackState.Playing(request),
                    inPictureInPicture = false,
                    changingConfigurations = true,
                ),
            )
        }
    }

    @Test
    fun failedAndIdleStatesDoNotSuspend() {
        assertEquals(
            LiveActivityBackgroundAction.NONE,
            LiveActivityLifecyclePolicy.backgroundAction(
                state = PlaybackState.Idle,
                inPictureInPicture = false,
                changingConfigurations = false,
            ),
        )
        assertEquals(
            LiveActivityBackgroundAction.NONE,
            LiveActivityLifecyclePolicy.backgroundAction(
                state = PlaybackState.Failed(
                    request = request(PlaybackMediaKind.MOVIE),
                    failure = PlaybackFailure(PlaybackFailureCategory.UNKNOWN),
                ),
                inPictureInPicture = false,
                changingConfigurations = false,
            ),
        )
    }

    private fun request(kind: PlaybackMediaKind) = PlaybackRequest(
        sourceId = "source",
        channelId = "content",
        mediaKind = kind,
        providerStreamId = if (kind == PlaybackMediaKind.SERIES_EPISODE) 7 else null,
    )
}
