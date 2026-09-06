package app.ownplay.player.download

import app.ownplay.player.testing.sourceBlockAfter
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloadLifecycleRecoveryContractTest {
    @Test
    fun `duplicate active enqueue keeps existing worker and paused stays paused`() {
        val repository = sourceText("src/main/java/app/ownplay/player/download/OfflineDownloadRepository.kt")
        val enqueueBlock = sourceBlockAfter(repository, "suspend fun enqueue(spec: OfflineDownloadSpec)")

        assertTrue(enqueueBlock.contains("DownloadStates.DOWNLOADING"))
        assertTrue(enqueueBlock.contains("DownloadStates.QUEUED"))
        assertTrue(enqueueBlock.contains("existingWorkPolicy = ExistingWorkPolicy.KEEP"))
        assertTrue(enqueueBlock.contains("DownloadStates.PAUSED -> return existing.downloadId"))
    }

    @Test
    fun `mobile resume reconciles pending WorkManager downloads`() {
        val featureRuntime = sourceText("src/main/java/app/ownplay/player/download/OfflineDownloadFeatureRuntime.kt")
        val activity = sourceText("src/main/java/app/ownplay/player/MainActivity.kt")
        val onResumeBlock = sourceBlockAfter(activity, "override fun onResume()")

        assertTrue(featureRuntime.contains("suspend fun reconcilePendingWork(): Int"))
        assertTrue(onResumeBlock.contains("downloadRuntime.reconcilePendingWork()"))
    }
}
