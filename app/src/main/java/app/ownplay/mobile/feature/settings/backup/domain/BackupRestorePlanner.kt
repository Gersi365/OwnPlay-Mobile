package app.ownplay.mobile.feature.settings.backup.domain

import app.ownplay.mobile.sources.domain.SourceType

data class ExistingBackupSource(
    val sourceId: String,
    val type: SourceType,
    val baseLocator: String,
)

enum class BackupSourceRestoreAction {
    MERGE_EXISTING,
    CREATE_DISABLED,
    CONFLICT,
}

data class BackupSourceResolution(
    val backupSourceId: String,
    val targetSourceId: String?,
    val action: BackupSourceRestoreAction,
)

data class BackupRestoreSummary(
    val sourceCount: Int = 0,
    val favoriteCount: Int = 0,
    val liveOrganizationItemCount: Int = 0,
    val libraryPreferenceItemCount: Int = 0,
    val includesDisplaySettings: Boolean = true,
    val includesPlaybackSettings: Boolean = true,
    val includesRefreshSettings: Boolean = false,
    val includesDownloadSettings: Boolean = true,
)

data class BackupRestorePlan(
    val sourceResolutions: List<BackupSourceResolution>,
    val summary: BackupRestoreSummary = BackupRestoreSummary(),
) {
    val hasConflicts: Boolean
        get() = sourceResolutions.any { it.action == BackupSourceRestoreAction.CONFLICT }
}

object BackupRestorePlanner {
    fun plan(
        backup: OwnPlayBackupPayload,
        existingSources: List<ExistingBackupSource>,
    ): BackupRestorePlan {
        val byId = existingSources.associateBy(ExistingBackupSource::sourceId)
        val byConnection = existingSources.groupBy { it.type to it.baseLocator }

        return BackupRestorePlan(
            sourceResolutions = backup.sources.map { source ->
                val sameId = byId[source.sourceId]
                when {
                    sameId != null && sameId.type == source.type && sameId.baseLocator == source.baseLocator ->
                        BackupSourceResolution(
                            backupSourceId = source.sourceId,
                            targetSourceId = sameId.sourceId,
                            action = BackupSourceRestoreAction.MERGE_EXISTING,
                        )

                    sameId != null -> BackupSourceResolution(
                        backupSourceId = source.sourceId,
                        targetSourceId = null,
                        action = BackupSourceRestoreAction.CONFLICT,
                    )

                    else -> {
                        val sameConnection = byConnection[source.type to source.baseLocator].orEmpty()
                        if (sameConnection.isEmpty()) {
                            BackupSourceResolution(
                                backupSourceId = source.sourceId,
                                targetSourceId = source.sourceId,
                                action = BackupSourceRestoreAction.CREATE_DISABLED,
                            )
                        } else {
                            // Catalog and personalization stable IDs are source-scoped. Merging a
                            // backup source into a different local sourceId would make those IDs
                            // non-reconcilable without an explicit provider-identity remapping layer.
                            BackupSourceResolution(
                                backupSourceId = source.sourceId,
                                targetSourceId = null,
                                action = BackupSourceRestoreAction.CONFLICT,
                            )
                        }
                    }
                }
            },
            summary = BackupRestoreSummary(
                sourceCount = backup.sources.size,
                favoriteCount = backup.mediaFavorites.size +
                    backup.channelPersonalization.count { it.favorite },
                liveOrganizationItemCount =
                    backup.liveCategoryPersonalization.size +
                    backup.livePlacementOverrides.size +
                    backup.customGroups.size +
                    backup.customGroupMemberships.size,
                libraryPreferenceItemCount = backup.categoryPersonalization.count {
                    it.kind == BackupCatalogKind.MOVIE || it.kind == BackupCatalogKind.SERIES
                },
                includesRefreshSettings = backup.sourceSettings.isNotEmpty(),
            ),
        )
    }
}
