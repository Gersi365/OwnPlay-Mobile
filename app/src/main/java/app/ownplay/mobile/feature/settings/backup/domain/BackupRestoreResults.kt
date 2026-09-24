package app.ownplay.mobile.feature.settings.backup.domain

sealed interface BackupExportResult {
    data class Success(val bytes: ByteArray) : BackupExportResult
    data object Failure : BackupExportResult
}

sealed interface BackupRestorePreview {
    data class Ready(val plan: BackupRestorePlan) : BackupRestorePreview
    data class Rejected(val issues: List<BackupValidationIssue>) : BackupRestorePreview
    data class Conflicted(val plan: BackupRestorePlan) : BackupRestorePreview
    data object StorageFailure : BackupRestorePreview
}

data class BackupRestoreReport(
    val mergedSources: Int,
    val createdDisabledSources: Int,
    val skippedCategoryPersonalization: Int,
    val skippedChannelPersonalization: Int,
    val skippedMediaFavorites: Int,
    val skippedCustomGroups: Int,
    val skippedLivePlacements: Int,
    val skippedCustomGroupMemberships: Int,
    val refreshScheduleSyncFailures: Int,
)

sealed interface BackupRestoreResult {
    data class Success(val report: BackupRestoreReport) : BackupRestoreResult
    data class Rejected(val issues: List<BackupValidationIssue>) : BackupRestoreResult
    data class Conflicted(val plan: BackupRestorePlan) : BackupRestoreResult
    data object StorageFailure : BackupRestoreResult
}

interface BackupRestoreRepository {
    suspend fun exportBackup(): BackupExportResult
    suspend fun previewRestore(bytes: ByteArray): BackupRestorePreview
    suspend fun restore(bytes: ByteArray): BackupRestoreResult
}
