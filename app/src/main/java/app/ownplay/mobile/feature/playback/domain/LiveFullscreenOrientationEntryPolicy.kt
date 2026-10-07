package app.ownplay.mobile.feature.playback.domain

internal enum class LivePhysicalOrientation {
    PORTRAIT,
    LANDSCAPE,
    TRANSITION,
}

internal object LiveFullscreenOrientationEntryPolicy {
    const val PORTRAIT_EXIT_DEBOUNCE_MS = 650L

    fun classifyPhysicalOrientation(orientationDegrees: Int): LivePhysicalOrientation = when {
        orientationDegrees in 60..120 || orientationDegrees in 240..300 ->
            LivePhysicalOrientation.LANDSCAPE
        orientationDegrees in 0..30 ||
            orientationDegrees in 330..359 ||
            orientationDegrees in 150..210 ->
            LivePhysicalOrientation.PORTRAIT
        else ->
            LivePhysicalOrientation.TRANSITION
    }

    fun isLandscapeOrientation(orientationDegrees: Int): Boolean =
        classifyPhysicalOrientation(orientationDegrees) == LivePhysicalOrientation.LANDSCAPE

    fun shouldEnterFullscreen(
        previousStableOrientation: LivePhysicalOrientation?,
        currentOrientation: LivePhysicalOrientation,
        livePreviewActive: Boolean,
        inPictureInPicture: Boolean,
    ): Boolean =
        currentOrientation == LivePhysicalOrientation.LANDSCAPE &&
            previousStableOrientation == LivePhysicalOrientation.PORTRAIT &&
            livePreviewActive &&
            !inPictureInPicture

    fun shouldExitFullscreen(
        currentOrientation: LivePhysicalOrientation,
        liveFullscreenActive: Boolean,
        inPictureInPicture: Boolean,
        landscapeObservedSinceFullscreenEntry: Boolean,
    ): Boolean =
        currentOrientation == LivePhysicalOrientation.PORTRAIT &&
            liveFullscreenActive &&
            landscapeObservedSinceFullscreenEntry &&
            !inPictureInPicture
}
