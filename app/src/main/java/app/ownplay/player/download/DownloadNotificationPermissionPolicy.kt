package app.ownplay.player.download

internal enum class DownloadNotificationPermissionState {
    NOT_REQUIRED,
    GRANTED,
    NEVER_REQUESTED,
    DENIED,
}

internal object DownloadNotificationPermissionPolicy {
    private const val RUNTIME_PERMISSION_API_LEVEL = 33

    fun resolve(
        sdkInt: Int,
        permissionGranted: Boolean,
        requestAttempted: Boolean,
    ): DownloadNotificationPermissionState =
        when {
            sdkInt < RUNTIME_PERMISSION_API_LEVEL ->
                DownloadNotificationPermissionState.NOT_REQUIRED
            permissionGranted -> DownloadNotificationPermissionState.GRANTED
            !requestAttempted -> DownloadNotificationPermissionState.NEVER_REQUESTED
            else -> DownloadNotificationPermissionState.DENIED
        }

    fun shouldRequest(state: DownloadNotificationPermissionState): Boolean =
        state == DownloadNotificationPermissionState.NEVER_REQUESTED
}
