package app.ownplay.mobile.feature.playback.domain

object LiveFullscreenOrientationEntryPolicy {
    fun isLandscapeOrientation(orientationDegrees: Int): Boolean =
        orientationDegrees in 60..120 || orientationDegrees in 240..300

    fun shouldEnterFullscreen(
        wasLandscape: Boolean?,
        isLandscape: Boolean,
        livePreviewActive: Boolean,
        inPictureInPicture: Boolean,
    ): Boolean =
        isLandscape &&
            wasLandscape == false &&
            livePreviewActive &&
            !inPictureInPicture
}
