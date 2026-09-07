package app.ownplay.player.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PlaybackOrientationIntent {
    PORTRAIT,
    FOLLOW_SYSTEM,
    SENSOR,
}

internal object PlaybackWindowPolicy {
    fun isPipEligible(
        pipSupported: Boolean,
        isPlaying: Boolean,
        playbackSurfaceActive: Boolean,
        pipEnabled: Boolean = true,
    ): Boolean = pipEnabled && pipSupported && isPlaying && playbackSurfaceActive

    fun orientationIntent(
        fullscreen: Boolean,
        livePreviewActive: Boolean = false,
        inPictureInPicture: Boolean,
    ): PlaybackOrientationIntent = when {
        inPictureInPicture -> PlaybackOrientationIntent.FOLLOW_SYSTEM
        fullscreen || livePreviewActive -> PlaybackOrientationIntent.SENSOR
        else -> PlaybackOrientationIntent.PORTRAIT
    }
}

class PlaybackWindowController(
    private val activity: Activity,
) {
    val pipSupported: Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private val _isInPictureInPictureMode =
        MutableStateFlow(activity.isInPictureInPictureMode)
    val isInPictureInPictureMode: StateFlow<Boolean> =
        _isInPictureInPictureMode.asStateFlow()

    private var isPlaying = false
    private var fullscreenRequested = false
    private var livePreviewActive = false
    private var pictureInPictureEnabled = true
    private var playbackSurfaceActive = false
    private var sourceRectHint: Rect? = null
    private var windowRoot: View? = null
    private var layoutListener: View.OnLayoutChangeListener? = null

    init {
        applyOrientationPolicy()
    }

    fun attachWindowRoot(view: View) {
        if (windowRoot === view) {
            refreshWindowState()
            return
        }
        detachWindowRoot()
        windowRoot = view
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (pictureInPictureEnabled && updateSourceRectHint()) {
                updatePictureInPictureParams()
            }
        }
        layoutListener = listener
        view.addOnLayoutChangeListener(listener)
        refreshWindowState()
    }

    fun updatePlaybackState(isPlaying: Boolean) {
        if (this.isPlaying == isPlaying) return
        this.isPlaying = isPlaying
        updatePictureInPictureParams()
    }

    fun updateFullscreenState(fullscreen: Boolean) {
        if (fullscreenRequested == fullscreen) return
        fullscreenRequested = fullscreen
        applyOrientationPolicy()
        applySystemBarPolicy()
        scheduleSystemBarPolicyRefresh()
    }

    fun updateLivePreviewState(active: Boolean) {
        if (livePreviewActive == active) return
        livePreviewActive = active
        applyOrientationPolicy()
    }

    fun updatePictureInPictureEnabled(enabled: Boolean) {
        if (pictureInPictureEnabled == enabled) return
        pictureInPictureEnabled = enabled
        if (enabled) {
            updateSourceRectHint()
        } else {
            sourceRectHint = null
        }
        updatePictureInPictureParams(force = true)
    }

    fun updatePlaybackSurfaceState(active: Boolean) {
        if (playbackSurfaceActive == active) return
        playbackSurfaceActive = active
        updatePictureInPictureParams()
    }

    fun refreshWindowState() {
        if (pictureInPictureEnabled) {
            updateSourceRectHint()
        }
        applyOrientationPolicy()
        applySystemBarPolicy()
        updatePictureInPictureParams()
    }

    fun requestPictureInPicture(): Boolean {
        if (!pipEligible() || activity.isInPictureInPictureMode || activity.isFinishing) {
            return false
        }
        return try {
            activity.enterPictureInPictureMode(buildPictureInPictureParams())
        } catch (_: IllegalStateException) {
            false
        }
    }

    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            requestPictureInPicture()
        }
    }

    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        _isInPictureInPictureMode.value = isInPictureInPictureMode
        applyOrientationPolicy()
        applySystemBarPolicy()
        if (!isInPictureInPictureMode) {
            scheduleSystemBarPolicyRefresh()
        }
        updatePictureInPictureParams()
    }

    fun release() {
        isPlaying = false
        fullscreenRequested = false
        livePreviewActive = false
        pictureInPictureEnabled = true
        playbackSurfaceActive = false
        detachWindowRoot()
        sourceRectHint = null
    }

    private fun pipEligible(): Boolean = PlaybackWindowPolicy.isPipEligible(
        pipSupported = pipSupported,
        isPlaying = isPlaying,
        playbackSurfaceActive = playbackSurfaceActive,
        pipEnabled = pictureInPictureEnabled,
    )

    private fun buildPictureInPictureParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        sourceRectHint?.takeIf { rect -> !rect.isEmpty() }?.let(builder::setSourceRectHint)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder
                .setAutoEnterEnabled(pipEligible())
                .setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun updatePictureInPictureParams(force: Boolean = false) {
        if (
            !pipSupported ||
            activity.isFinishing ||
            (!pictureInPictureEnabled && !force)
        ) {
            return
        }
        try {
            activity.setPictureInPictureParams(buildPictureInPictureParams())
        } catch (_: IllegalStateException) {
            // Window transitions can temporarily reject PiP parameter updates.
        }
    }

    private fun updateSourceRectHint(): Boolean {
        val view = windowRoot
        val nextRect = if (view != null && view.isAttachedToWindow) {
            val rect = Rect()
            if (view.getGlobalVisibleRect(rect) && !rect.isEmpty()) Rect(rect) else null
        } else {
            null
        }
        if (sourceRectHint == nextRect) return false
        sourceRectHint = nextRect
        return true
    }

    private fun detachWindowRoot() {
        val view = windowRoot
        val listener = layoutListener
        if (view != null && listener != null) {
            view.removeOnLayoutChangeListener(listener)
        }
        windowRoot = null
        layoutListener = null
    }

    private fun isPictureInPictureOwned(): Boolean =
        _isInPictureInPictureMode.value || activity.isInPictureInPictureMode

    private fun applyOrientationPolicy() {
        val target = when (
            PlaybackWindowPolicy.orientationIntent(
                fullscreen = fullscreenRequested,
                livePreviewActive = livePreviewActive,
                inPictureInPicture = isPictureInPictureOwned(),
            )
        ) {
            PlaybackOrientationIntent.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            PlaybackOrientationIntent.FOLLOW_SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            PlaybackOrientationIntent.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        if (activity.requestedOrientation != target) {
            activity.requestedOrientation = target
        }
    }

    private fun applySystemBarPolicy() {
        if (isPictureInPictureOwned() || activity.isFinishing) return
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreenRequested) {
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                hide(WindowInsetsCompat.Type.statusBars())
                show(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }

    private fun scheduleSystemBarPolicyRefresh() {
        val view = windowRoot ?: activity.window.decorView
        view.post {
            if (!activity.isFinishing) {
                applySystemBarPolicy()
            }
        }
    }
}
