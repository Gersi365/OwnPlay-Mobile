package app.ownplay.player.download

import app.ownplay.player.testing.sourceBlockAfter
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotificationUxContractTest {
    @Test
    fun freshEnqueueRequestsPermissionContextuallyWithoutGatingRepository() {
        val runtime = sourceText(
            "src/main/java/app/ownplay/player/download/OfflineDownloadFeatureRuntime.kt",
        )
        val enqueue = sourceBlockAfter(
            runtime,
            "suspend fun enqueue(spec: OfflineDownloadSpec): String",
        )
        assertTrue(enqueue.contains("DownloadNotificationPermissionBridge.requestIfNeeded()"))
        assertTrue(enqueue.contains("return repository.enqueue(spec)"))
    }

    @Test
    fun activityOwnsPermissionLauncherAndDoesNotRequestAtStartup() {
        val source = sourceText("src/main/java/app/ownplay/player/MainActivity.kt")
        assertTrue(
            source.contains(
                "registerForActivityResult(ActivityResultContracts.RequestPermission())",
            ),
        )
        assertTrue(source.contains("DownloadNotificationPermissionBridge.register("))
        val requestMethod = sourceBlockAfter(
            source,
            "private fun requestDownloadNotificationPermissionIfNeeded()",
        )
        assertTrue(requestMethod.contains("DownloadNotificationPermissionPolicy.resolve("))
        assertTrue(requestMethod.contains("markRequestAttempted()"))
        assertTrue(
            requestMethod.contains(
                "downloadNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)",
            ),
        )
        val onCreate = sourceBlockAfter(source, "override fun onCreate(savedInstanceState: Bundle?)")
        assertFalse(
            onCreate.contains(
                "downloadNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)",
            ),
        )
    }

    @Test
    fun workerKeepsStableLowNoiseForegroundNotificationContract() {
        val worker = sourceText(
            "src/main/java/app/ownplay/player/download/OfflineDownloadWorker.kt",
        )
        assertTrue(worker.contains("NotificationManager.IMPORTANCE_LOW"))
        assertTrue(worker.contains(".setOnlyAlertOnce(true)"))
        assertTrue(worker.contains(".setOngoing(true)"))
        assertTrue(worker.contains(".setWhen(DownloadNotificationOrder.eventTime(row.createdAtEpochMillis))"))
        assertTrue(worker.contains(".setSortKey(DownloadNotificationOrder.sortKey(row.createdAtEpochMillis, row.downloadId))"))
        assertTrue(worker.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC"))
        assertTrue(
            worker.contains(
                "NOTIFICATION_ID_BASE + (row.downloadId.hashCode() and 0x3fffffff)",
            ),
        )
        assertFalse(worker.contains("setDeleteIntent("))
    }

    @Test
    fun manifestRetainsNotificationAndDataSyncForegroundPermissions() {
        val manifest = sourceText("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"dataSync\""))
    }
}
