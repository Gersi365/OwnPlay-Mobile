package app.ownplay.mobile

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.graphics.drawable.Icon
import android.util.Rational
import android.view.KeyEvent
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.ownplay.mobile.app.OwnPlayApp
import app.ownplay.mobile.design.OwnPlayTheme
import app.ownplay.mobile.downloads.data.DownloadNotificationNavigationContract
import app.ownplay.mobile.downloads.domain.DownloadDetailsNavigation
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.feature.playback.data.PlaybackPictureInPictureActionReceiver
import app.ownplay.mobile.feature.playback.domain.LiveFullscreenOrientationEntryPolicy
import app.ownplay.mobile.feature.playback.domain.LivePhysicalOrientation
import app.ownplay.mobile.feature.playback.domain.PlaybackAutomaticPictureInPicturePolicy
import app.ownplay.mobile.feature.playback.domain.PlaybackPictureInPictureActionPolicy
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferences
import app.ownplay.mobile.feature.playback.domain.PlaybackPictureInPicturePolicy
import app.ownplay.mobile.feature.playback.domain.PlaybackPeriodicCheckpointPolicy
import app.ownplay.mobile.feature.playback.domain.PlaybackPresentation
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionState
import app.ownplay.mobile.feature.settings.data.SourceRefreshNotificationController
import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var latestPlaybackState = PlaybackSessionState()
    private var playbackPreferences = PlaybackPreferences()
    private var lastStablePhysicalOrientation: LivePhysicalOrientation? = null
    private var latestPhysicalOrientation = LivePhysicalOrientation.TRANSITION
    private var pendingLiveFullscreenPortraitExit: Job? = null
    private var liveFullscreenLandscapeObserved = false
    private var liveFullscreenOrientationLocked = false
    private val physicalOrientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val physicalOrientation =
                    LiveFullscreenOrientationEntryPolicy.classifyPhysicalOrientation(orientation)
                latestPhysicalOrientation = physicalOrientation
                if (physicalOrientation == LivePhysicalOrientation.TRANSITION) {
                    cancelPendingLiveFullscreenPortraitExit()
                    return
                }

                val previousStableOrientation = lastStablePhysicalOrientation
                lastStablePhysicalOrientation = physicalOrientation
                val state = ownPlayApplication.services.playbackSessionController.state.value
                val livePreviewActive =
                    state.target is PlaybackTarget.LiveChannel &&
                        state.presentation == PlaybackPresentation.PREVIEW
                val liveFullscreenActive =
                    state.target is PlaybackTarget.LiveChannel &&
                        state.presentation == PlaybackPresentation.FULLSCREEN

                if (
                    liveFullscreenActive &&
                    physicalOrientation == LivePhysicalOrientation.LANDSCAPE
                ) {
                    liveFullscreenLandscapeObserved = true
                    cancelPendingLiveFullscreenPortraitExit()
                }

                if (
                    LiveFullscreenOrientationEntryPolicy.shouldEnterFullscreen(
                        previousStableOrientation = previousStableOrientation,
                        currentOrientation = physicalOrientation,
                        livePreviewActive = livePreviewActive,
                        inPictureInPicture = isInPictureInPictureMode,
                    )
                ) {
                    ownPlayApplication.services.playbackSessionController.enterFullscreen()
                    return
                }

                if (
                    LiveFullscreenOrientationEntryPolicy.shouldExitFullscreen(
                        currentOrientation = physicalOrientation,
                        liveFullscreenActive = liveFullscreenActive,
                        inPictureInPicture = isInPictureInPictureMode,
                        landscapeObservedSinceFullscreenEntry = liveFullscreenLandscapeObserved,
                    )
                ) {
                    scheduleLiveFullscreenPortraitExit()
                } else if (physicalOrientation != LivePhysicalOrientation.PORTRAIT) {
                    cancelPendingLiveFullscreenPortraitExit()
                }
            }
        }
    }
    private val downloadDetailsNavigation = MutableStateFlow<DownloadDetailsNavigation?>(null)
    private val sourceSettingsNavigation = MutableStateFlow<SourceId?>(null)
    private val openRecordingsRequest = MutableStateFlow(0L)
    private var recordingNavigationSequence = 0L
    private val ownPlayApplication: OwnPlayApplication
        get() = application as OwnPlayApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptDownloadNavigation(intent)
        acceptRecordingsNavigation(intent)
        acceptSourceSettingsNavigation(intent)

        lifecycleScope.launch {
            ownPlayApplication.services.playbackSessionController.state.collect { state ->
                latestPlaybackState = state
                updatePlaybackPresentationWindow(state)
                updatePictureInPictureParams(state)
            }
        }
        lifecycleScope.launch {
            ownPlayApplication.services.playbackPreferencesRepository.preferences.collect { preferences ->
                playbackPreferences = preferences
                ownPlayApplication.services.playbackSessionController
                    .setPlayerVolume(preferences.playerVolume)
                updatePictureInPictureParams(latestPlaybackState)
            }
        }
        lifecycleScope.launch {
            while (true) {
                delay(PlaybackPeriodicCheckpointPolicy.INTERVAL_MS)
                if (PlaybackPeriodicCheckpointPolicy.shouldCheckpoint(latestPlaybackState)) {
                    ownPlayApplication.services.playbackSessionController.checkpointProgress()
                }
            }
        }

        setContent {
            val downloadNavigation by downloadDetailsNavigation.collectAsState()
            val sourceSettingsRequest by sourceSettingsNavigation.collectAsState()
            val recordingsRequest by openRecordingsRequest.collectAsState()
            OwnPlayTheme {
                OwnPlayApp(
                    downloadDetailsNavigation = downloadNavigation,
                    onDownloadDetailsNavigationConsumed = {
                        if (downloadDetailsNavigation.value == downloadNavigation) {
                            downloadDetailsNavigation.value = null
                        }
                    },
                    sourceSettingsNavigation = sourceSettingsRequest,
                    openRecordingsRequestId = recordingsRequest,
                    onOpenRecordingsRequestConsumed = {
                        if (openRecordingsRequest.value == recordingsRequest) {
                            openRecordingsRequest.value = 0L
                        }
                    },
                    onSourceSettingsNavigationConsumed = {
                        if (sourceSettingsNavigation.value == sourceSettingsRequest) {
                            sourceSettingsNavigation.value = null
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptDownloadNavigation(intent)
        acceptRecordingsNavigation(intent)
        acceptSourceSettingsNavigation(intent)
    }

    private fun acceptDownloadNavigation(intent: Intent?) {
        if (intent?.action != DownloadNotificationNavigationContract.ACTION_OPEN_DETAILS) return
        val downloadId = intent.getStringExtra(DownloadNotificationNavigationContract.EXTRA_DOWNLOAD_ID)
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { DownloadId(it) }.getOrNull() }
            ?: return
        val sourceId = intent.getStringExtra(DownloadNotificationNavigationContract.EXTRA_SOURCE_ID)
            ?.takeIf(String::isNotBlank)
            ?.let(::SourceId)
            ?: return
        val mediaKind = intent.getStringExtra(DownloadNotificationNavigationContract.EXTRA_MEDIA_KIND)
            ?.let { runCatching { DownloadMediaKind.valueOf(it) }.getOrNull() }
            ?: return
        val contentId = intent.getStringExtra(DownloadNotificationNavigationContract.EXTRA_CONTENT_ID)
            ?.takeIf(String::isNotBlank)
            ?: return
        downloadDetailsNavigation.value = DownloadDetailsNavigation(
            downloadId = downloadId,
            sourceId = sourceId,
            mediaKind = mediaKind,
            contentId = contentId,
        )
    }

    private fun acceptRecordingsNavigation(intent: Intent?) {
        if (intent?.action != DownloadNotificationNavigationContract.ACTION_OPEN_RECORDINGS) return
        recordingNavigationSequence += 1L
        openRecordingsRequest.value = recordingNavigationSequence
    }

    private fun acceptSourceSettingsNavigation(intent: Intent?) {
        if (intent?.action != SourceRefreshNotificationController.ACTION_OPEN_SOURCE_SETTINGS) return
        val sourceId = intent.getStringExtra(SourceRefreshNotificationController.EXTRA_SOURCE_ID)
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { SourceId(it) }.getOrNull() }
            ?: return
        sourceSettingsNavigation.value = sourceId
    }

    override fun onResume() {
        super.onResume()
        if (physicalOrientationListener.canDetectOrientation()) {
            physicalOrientationListener.enable()
        }
        ownPlayApplication.services.liveRecordingScheduler.restoreScheduledAlarms()
        lifecycleScope.launch {
            ownPlayApplication.services.playbackSessionController.revalidateActiveTarget()
        }
    }

    override fun onPause() {
        cancelPendingLiveFullscreenPortraitExit()
        physicalOrientationListener.disable()
        super.onPause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isInPictureInPictureMode) return

        if (!playbackPreferences.automaticPictureInPicture) {
            if (latestPlaybackState.target != null) {
                ownPlayApplication.services.playbackSessionController.clear()
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        if (!PlaybackAutomaticPictureInPicturePolicy.isEligible(playbackPreferences, latestPlaybackState)) return

        if (enterPictureInPictureMode(buildPictureInPictureParams(latestPlaybackState))) {
            ownPlayApplication.services.playbackSessionController
                .onPictureInPictureModeChanged(true)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ownPlayApplication.services.playbackSessionController
            .onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (
            latestPlaybackState.target != null &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.05f else -0.05f
            val next = (playbackPreferences.playerVolume + delta).coerceIn(0f, 1f)
            playbackPreferences = playbackPreferences.copy(playerVolume = next)
            ownPlayApplication.services.playbackSessionController.setPlayerVolume(next)
            lifecycleScope.launch {
                ownPlayApplication.services.playbackPreferencesRepository.setPlayerVolume(next)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    internal fun requestManualLiveFullscreen(): Boolean {
        if (latestPlaybackState.target !is PlaybackTarget.LiveChannel) return false
        if (latestPlaybackState.presentation != PlaybackPresentation.PREVIEW) return false

        ownPlayApplication.services.playbackSessionController.enterFullscreen()
        return true
    }

    internal fun exitLiveFullscreen(): Boolean {
        if (
            latestPlaybackState.target !is PlaybackTarget.LiveChannel ||
            latestPlaybackState.presentation != PlaybackPresentation.FULLSCREEN
        ) return false

        ownPlayApplication.services.playbackSessionController.returnToPreview()
        return true
    }

    internal fun requestOwnPlayPictureInPicture(): Boolean {
        if (isInPictureInPictureMode) return true
        if (!PlaybackPictureInPicturePolicy.isEligible(latestPlaybackState)) return false
        val entered = enterPictureInPictureMode(buildPictureInPictureParams(latestPlaybackState))
        if (entered) {
            ownPlayApplication.services.playbackSessionController
                .onPictureInPictureModeChanged(true)
        }
        return entered
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            ownPlayApplication.services.playbackSessionController.clear()
        }
        super.onDestroy()
    }

    private fun updatePlaybackPresentationWindow(state: PlaybackSessionState) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val fullscreen =
            state.target != null && state.presentation == PlaybackPresentation.FULLSCREEN
        val liveFullscreen =
            state.target is PlaybackTarget.LiveChannel &&
                state.presentation == PlaybackPresentation.FULLSCREEN

        updateLiveFullscreenOrientationLock(liveFullscreen)

        if (fullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else if (state.presentation != PlaybackPresentation.PICTURE_IN_PICTURE) {
            if (!isInPictureInPictureMode) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun updateLiveFullscreenOrientationLock(liveFullscreen: Boolean) {
        if (liveFullscreen) {
            if (!liveFullscreenOrientationLocked) {
                liveFullscreenOrientationLocked = true
                liveFullscreenLandscapeObserved =
                    latestPhysicalOrientation == LivePhysicalOrientation.LANDSCAPE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            return
        }

        cancelPendingLiveFullscreenPortraitExit()
        liveFullscreenLandscapeObserved = false
        if (liveFullscreenOrientationLocked) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            liveFullscreenOrientationLocked = false
        }
    }

    private fun scheduleLiveFullscreenPortraitExit() {
        if (pendingLiveFullscreenPortraitExit != null) return
        pendingLiveFullscreenPortraitExit = lifecycleScope.launch {
            delay(LiveFullscreenOrientationEntryPolicy.PORTRAIT_EXIT_DEBOUNCE_MS)
            pendingLiveFullscreenPortraitExit = null

            val state = ownPlayApplication.services.playbackSessionController.state.value
            if (
                LiveFullscreenOrientationEntryPolicy.shouldExitFullscreen(
                    currentOrientation = latestPhysicalOrientation,
                    liveFullscreenActive =
                        state.target is PlaybackTarget.LiveChannel &&
                            state.presentation == PlaybackPresentation.FULLSCREEN,
                    inPictureInPicture = isInPictureInPictureMode,
                    landscapeObservedSinceFullscreenEntry = liveFullscreenLandscapeObserved,
                )
            ) {
                ownPlayApplication.services.playbackSessionController.returnToPreview()
            }
        }
    }

    private fun cancelPendingLiveFullscreenPortraitExit() {
        pendingLiveFullscreenPortraitExit?.cancel()
        pendingLiveFullscreenPortraitExit = null
    }

    private fun updatePictureInPictureParams(state: PlaybackSessionState) {
        setPictureInPictureParams(buildPictureInPictureParams(state))
    }

    private fun buildPictureInPictureParams(state: PlaybackSessionState): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))

        if (PlaybackPictureInPictureActionPolicy.showsPlayPause(state)) {
            val playing = state.playWhenReady
            val actionIntent = Intent(
                this,
                PlaybackPictureInPictureActionReceiver::class.java,
            ).setAction(PlaybackPictureInPictureActionReceiver.ACTION_TOGGLE_PLAY_PAUSE)
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                PIP_PLAY_PAUSE_REQUEST_CODE,
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setActions(
                listOf(
                    RemoteAction(
                        Icon.createWithResource(
                            this,
                            if (playing) {
                                android.R.drawable.ic_media_pause
                            } else {
                                android.R.drawable.ic_media_play
                            },
                        ),
                        if (playing) "Pause" else "Play",
                        if (playing) "Pause playback" else "Play playback",
                        pendingIntent,
                    ),
                ),
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(
                PlaybackAutomaticPictureInPicturePolicy.isEligible(playbackPreferences, state),
            )
        }

        return builder.build()
    }

    private companion object {
        const val PIP_PLAY_PAUSE_REQUEST_CODE = 7101
    }
}
