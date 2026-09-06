package app.ownplay.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.ownplay.player.download.OfflineDownloadFeatureRuntime
import app.ownplay.player.personalization.AppDeviceProfile
import app.ownplay.player.personalization.AppDeviceProfileSelection
import app.ownplay.player.personalization.AppDeviceProfileStore
import app.ownplay.player.playback.LiveActivityBackgroundAction
import app.ownplay.player.playback.LiveActivityLifecyclePolicy
import app.ownplay.player.playback.PlaybackInteractionBridge
import app.ownplay.player.playback.PlaybackMediaKind
import app.ownplay.player.playback.PlaybackState
import app.ownplay.player.ui.DownloadPlaybackBridge
import app.ownplay.player.ui.OrientationSetupLoadingSurface
import app.ownplay.player.ui.OwnPlayRoot
import app.ownplay.player.ui.PictureInPicturePlaybackSurface
import app.ownplay.player.ui.PlaybackOriginBadge
import app.ownplay.player.ui.PlaybackWindowController
import app.ownplay.player.ui.library.LibraryPlaybackScreen
import app.ownplay.player.ui.library.LibraryPlaybackSession
import app.ownplay.player.ui.theme.OwnPlayTheme
import app.ownplay.player.ui.tv.TvBackgroundPlaybackAction
import app.ownplay.player.ui.tv.TvPlaybackLifecyclePolicy
import app.ownplay.player.ui.tv.TvRemoteActionGuard
import app.ownplay.player.ui.tv.TvRemoteActionKind
import app.ownplay.player.ui.tv.TvRemoteKeySuppression
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val DOUBLE_TAP_SEEK_MILLIS = 10_000L

class MainActivity : ComponentActivity() {
    private lateinit var runtime: OwnPlayAppRuntime
    private var offlineDownloadRuntime: OfflineDownloadFeatureRuntime? = null
    private lateinit var playbackWindowController: PlaybackWindowController
    private lateinit var appDeviceProfileStore: AppDeviceProfileStore
    private lateinit var playbackGestureDetector: GestureDetector
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tvRemoteActionGuard = TvRemoteActionGuard()
    private val tvRemoteKeySuppression = TvRemoteKeySuppression()
    private var playbackFullscreen = false
    private var tvRemoteGuardEnabled = false
    private var exitConfirmationDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = (application as OwnPlayApplication).runtime
        offlineDownloadRuntime = if (BuildConfig.IS_TV_BUILD) {
            null
        } else {
            OfflineDownloadFeatureRuntime(applicationContext)
        }
        appDeviceProfileStore = AppDeviceProfileStore(applicationContext)
        playbackWindowController = PlaybackWindowController(this)
        playbackGestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (!playbackFullscreen) return false
                    val mediaKind = currentPlaybackMediaKind()
                    if (
                        mediaKind != PlaybackMediaKind.MOVIE &&
                        mediaKind != PlaybackMediaKind.SERIES_EPISODE
                    ) {
                        return false
                    }
                    val deltaMillis = if (e.x < window.decorView.width / 2f) {
                        -DOUBLE_TAP_SEEK_MILLIS
                    } else {
                        DOUBLE_TAP_SEEK_MILLIS
                    }
                    return PlaybackInteractionBridge.seekBy(deltaMillis)
                }
            },
        )
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (PlaybackInteractionBridge.handleBack()) return
                    showExitConfirmation()
                }
            },
        )
        playbackWindowController.refreshWindowState()
        enableEdgeToEdge()
        hideStatusBar()
        setContent {
            val isInPictureInPictureMode by
                playbackWindowController.isInPictureInPictureMode.collectAsState()
            val deviceProfileSelection by appDeviceProfileStore.observeSelection().collectAsState(
                initial = AppDeviceProfileSelection.Loading,
            )
            val configuredProfile =
                (deviceProfileSelection as? AppDeviceProfileSelection.Configured)
                    ?.settings
                    ?.profile
            val playbackOrigin by runtime.playbackController.resolvedOrigin.collectAsState()
            val downloadRuntime = offlineDownloadRuntime
            var downloadPlaybackSession by remember {
                mutableStateOf<LibraryPlaybackSession?>(null)
            }
            val downloadPlaybackOwner = remember { Any() }

            SideEffect {
                val usesDpad = configuredProfile?.usesDpad == true
                PlaybackInteractionBridge.setDpadMode(usesDpad)
                playbackWindowController.updateFullscreenSensorRotationEnabled(!usesDpad)
                playbackWindowController.updatePictureInPictureEnabled(!usesDpad)
                if (configuredProfile != AppDeviceProfile.SMARTPHONE) {
                    playbackWindowController.updateLivePreviewRotationEnabled(false)
                }
                tvRemoteGuardEnabled = usesDpad
                if (!usesDpad) tvRemoteKeySuppression.clear()
            }

            DisposableEffect(downloadPlaybackOwner, downloadRuntime) {
                if (downloadRuntime == null) {
                    onDispose { }
                } else {
                    DownloadPlaybackBridge.register(downloadPlaybackOwner) { download ->
                        activityScope.launch {
                            val request = downloadRuntime.playbackRequest(download.downloadId)
                            if (request == null) {
                                downloadRuntime.reconcileCompletedFiles()
                                Toast.makeText(
                                    applicationContext,
                                    "The offline file is unavailable. Download it again to restore offline playback.",
                                    Toast.LENGTH_LONG,
                                ).show()
                                return@launch
                            }
                            val progress = downloadRuntime.playbackProgress(download.downloadId)
                            runtime.playbackController.start(request)
                            downloadPlaybackSession = LibraryPlaybackSession(
                                download = download,
                                initialPositionMs = progress
                                    ?.takeIf { !it.completed }
                                    ?.positionMs
                                    ?.coerceAtLeast(0L)
                                    ?: 0L,
                            )
                        }
                    }
                    onDispose {
                        DownloadPlaybackBridge.clear(downloadPlaybackOwner)
                    }
                }
            }

            OwnPlayTheme(deviceProfile = configuredProfile) {
                when (deviceProfileSelection) {
                    AppDeviceProfileSelection.Loading -> {
                        OrientationSetupLoadingSurface()
                    }
                    is AppDeviceProfileSelection.Configured -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            OwnPlayRoot(
                                runtime = runtime,
                                rotationFullscreenEnabled = liveRotationFullscreenEnabled(
                                    isSmartphone =
                                        configuredProfile == AppDeviceProfile.SMARTPHONE,
                                    inPictureInPicture = isInPictureInPictureMode,
                                ),
                                onPlaybackFullscreenChanged = { isFullscreen ->
                                    holdTvRemoteTransitionLock()
                                    playbackFullscreen = isFullscreen
                                    playbackWindowController.updateFullscreenState(isFullscreen)
                                    if (!isFullscreen) hideStatusBar()
                                },
                                onPlaybackSurfaceActiveChanged =
                                    playbackWindowController::updatePlaybackSurfaceState,
                                onLivePreviewActiveChanged = { previewActive ->
                                    playbackWindowController.updateLivePreviewRotationEnabled(
                                        previewActive &&
                                            configuredProfile == AppDeviceProfile.SMARTPHONE,
                                    )
                                },
                            )

                            when {
                                isInPictureInPictureMode -> {
                                    PictureInPicturePlaybackSurface(
                                        videoOutput = runtime.playbackVideoOutput,
                                        mediaKind = currentPlaybackMediaKind(),
                                        liveWasFullscreen = playbackFullscreen,
                                        onProgress = { positionMs, durationMs ->
                                            val request = when (
                                                val state = runtime.playbackController.state.value
                                            ) {
                                                is PlaybackState.Playing -> state.request
                                                is PlaybackState.Paused -> state.request
                                                else -> null
                                            }
                                            if (request != null && downloadRuntime != null) {
                                                activityScope.launch {
                                                    downloadRuntime.savePlaybackProgress(
                                                        request = request,
                                                        positionMs = positionMs,
                                                        durationMs = durationMs,
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                                downloadPlaybackSession != null && downloadRuntime != null -> {
                                    val session = downloadPlaybackSession ?: return@OwnPlayTheme
                                    LibraryPlaybackScreen(
                                        runtime = runtime,
                                        session = session,
                                        onExit = {
                                            downloadPlaybackSession = null
                                            DownloadPlaybackBridge.notifyPlaybackClosed(
                                                session.download.downloadId,
                                            )
                                        },
                                        onProgress = { positionMs, durationMs ->
                                            activityScope.launch {
                                                downloadRuntime.savePlaybackProgress(
                                                    downloadId = session.download.downloadId,
                                                    positionMs = positionMs,
                                                    durationMs = durationMs,
                                                )
                                            }
                                        },
                                        onFullscreenStateChanged = { isFullscreen ->
                                            holdTvRemoteTransitionLock()
                                            playbackFullscreen = isFullscreen
                                            playbackWindowController.updateFullscreenState(isFullscreen)
                                            playbackWindowController.updatePlaybackSurfaceState(isFullscreen)
                                            if (!isFullscreen) hideStatusBar()
                                        },
                                        backContentDescription = "Back to Downloads",
                                        contextLabel = "Downloads",
                                    )
                                    playbackOrigin?.let { origin ->
                                        PlaybackOriginBadge(
                                            origin = origin,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(top = 10.dp, end = 12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        playbackWindowController.attachWindowRoot(findViewById(android.R.id.content))
        activityScope.launch {
            appDeviceProfileStore.observeSelection().collectLatest { selection ->
                if (selection is AppDeviceProfileSelection.Configured) {
                    playbackWindowController.updateAppOrientation(
                        selection.settings.effectiveOrientation,
                    )
                }
            }
        }
        activityScope.launch {
            runtime.playbackController.state.collectLatest { state ->
                playbackWindowController.updatePlaybackState(state is PlaybackState.Playing)
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) {
                onBackPressedDispatcher.onBackPressed()
            }
            return true
        }
        if (tvRemoteGuardEnabled && event.isRemoteActivationKey()) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount > 0) {
                        tvRemoteKeySuppression.suppress(event.keyCode)
                        return true
                    }
                    if (
                        !tvRemoteActionGuard.tryAcquire(
                            nowMillis = SystemClock.elapsedRealtime(),
                            actionId = event.keyCode,
                        )
                    ) {
                        tvRemoteKeySuppression.suppress(event.keyCode)
                        return true
                    }
                    tvRemoteKeySuppression.allow(event.keyCode)
                }
                KeyEvent.ACTION_UP -> {
                    if (tvRemoteActionGuard.isGloballyBlocked(SystemClock.elapsedRealtime())) {
                        tvRemoteKeySuppression.consumeRelease(event.keyCode)
                        return true
                    }
                    if (tvRemoteKeySuppression.consumeRelease(event.keyCode)) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        playbackGestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (::runtime.isInitialized) {
            PlaybackInteractionBridge.resumeLifecycleSuspended(runtime.playbackVideoOutput)
            runtime.playbackController.resumeAfterBackground()
        }
        hideStatusBar()
        offlineDownloadRuntime?.let { downloadRuntime ->
            activityScope.launch {
                downloadRuntime.reconcileCompletedFiles()
                downloadRuntime.reconcilePendingWork()
            }
        }
    }

    override fun onStop() {
        if (::runtime.isInitialized) {
            val state = runtime.playbackController.state.value
            when (
                LiveActivityLifecyclePolicy.backgroundAction(
                    state = state,
                    inPictureInPicture = isInPictureInPictureMode,
                    changingConfigurations = isChangingConfigurations,
                )
            ) {
                LiveActivityBackgroundAction.SUSPEND_AND_RETAIN_SURFACE -> {
                    PlaybackInteractionBridge.suspendCurrentForLifecycle(runtime.playbackVideoOutput)
                    runtime.playbackController.suspendForBackground()
                }
                LiveActivityBackgroundAction.NONE -> {
                    if (
                        tvRemoteGuardEnabled &&
                        !isInPictureInPictureMode &&
                        !isChangingConfigurations
                    ) {
                        when (TvPlaybackLifecyclePolicy.backgroundAction(state)) {
                            TvBackgroundPlaybackAction.NONE -> Unit
                            TvBackgroundPlaybackAction.SUSPEND -> {
                                runtime.playbackController.suspendForBackground()
                            }
                        }
                    }
                }
            }
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        playbackWindowController.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        holdTvRemoteTransitionLock()
        playbackWindowController.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        playbackWindowController.refreshWindowState()
        hideStatusBar()
    }

    override fun onDestroy() {
        exitConfirmationDialog?.dismiss()
        exitConfirmationDialog = null
        if (isFinishing && ::runtime.isInitialized) {
            runtime.playbackController.stop()
        }
        PlaybackInteractionBridge.discardLifecycleSuspendedSurface()
        activityScope.cancel()
        offlineDownloadRuntime?.close()
        offlineDownloadRuntime = null
        playbackWindowController.release()
        super.onDestroy()
    }

    private fun currentPlaybackMediaKind(): PlaybackMediaKind? =
        when (val state = runtime.playbackController.state.value) {
            PlaybackState.Idle -> null
            is PlaybackState.Loading -> state.request.mediaKind
            is PlaybackState.Playing -> state.request.mediaKind
            is PlaybackState.Paused -> state.request.mediaKind
            is PlaybackState.Failed -> state.request.mediaKind
        }

    private fun holdTvRemoteTransitionLock() {
        if (!tvRemoteGuardEnabled) return
        tvRemoteActionGuard.extendBlock(
            nowMillis = SystemClock.elapsedRealtime(),
            kind = TvRemoteActionKind.TRANSITION,
        )
    }

    private fun showExitConfirmation() {
        if (isFinishing || exitConfirmationDialog?.isShowing == true) return
        exitConfirmationDialog = AlertDialog.Builder(this)
            .setTitle("Exit OwnPlay?")
            .setMessage("Are you sure you want to close the app?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Exit") { _, _ -> finish() }
            .setOnDismissListener { exitConfirmationDialog = null }
            .show()
    }

    private fun hideStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }
}

private fun KeyEvent.isRemoteActivationKey(): Boolean =
    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
        keyCode == KeyEvent.KEYCODE_BUTTON_A ||
        keyCode == KeyEvent.KEYCODE_BUTTON_SELECT ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
        keyCode == KeyEvent.KEYCODE_BACK

/**
 * PiP owns the active playback surface. Rotation-triggered Live presentation changes must stay
 * inert until PiP exits so the hidden Preview/Fullscreen tree cannot steal the video surface.
 */
internal fun liveRotationFullscreenEnabled(
    isSmartphone: Boolean,
    inPictureInPicture: Boolean,
): Boolean = isSmartphone && !inPictureInPicture
