package app.ownplay.mobile.feature.settings.backup.data

import androidx.room.withTransaction
import app.ownplay.mobile.data.db.BackupDao
import app.ownplay.mobile.data.db.CategoryPersonalizationEntity
import app.ownplay.mobile.data.db.ChannelPersonalizationEntity
import app.ownplay.mobile.data.db.CustomGroupEntity
import app.ownplay.mobile.data.db.CustomGroupMembershipEntity
import app.ownplay.mobile.data.db.LiveCategoryScopePersonalizationEntity
import app.ownplay.mobile.data.db.LiveChannelMembershipPersonalizationEntity
import app.ownplay.mobile.data.db.LiveOrganizationPreferenceEntity
import app.ownplay.mobile.data.db.MediaFavoriteEntity
import app.ownplay.mobile.data.db.OwnPlayDatabase
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.data.db.SourceEntity
import app.ownplay.mobile.feature.live.domain.LiveOrganizationMode
import app.ownplay.mobile.feature.live.domain.ProviderLiveOrganizationContract
import app.ownplay.mobile.feature.settings.backup.domain.*
import app.ownplay.mobile.feature.settings.domain.SourceRefreshSchedule
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceType
import java.time.Instant

internal class RoomBackupRestoreRepository(
    private val database: OwnPlayDatabase,
    private val sourceDao: SourceDao,
    private val backupDao: BackupDao,
    private val preferences: BackupPreferenceGateway,
    private val codec: BackupJsonCodec,
    private val now: () -> Instant = Instant::now,
) : BackupRestoreRepository {
    override suspend fun exportBackup(): BackupExportResult = try {
        val room = database.withTransaction { captureRoomSnapshot() }
        val sourceIds = room.sources.map(SourceEntity::sourceId)
        val preferenceSnapshot = preferences.snapshot(sourceIds)
        val activeSourceId = (
            preferenceSnapshot.intendedActiveSourceId
                ?: preferenceSnapshot.activeSourceId
            )?.takeIf { it in sourceIds }
        val sourceSettings = room.sources.map { source ->
            BackupSourceSettings(
                sourceId = source.sourceId,
                refreshSchedule = preferenceSnapshot.refreshSchedules[source.sourceId]
                    ?: SourceRefreshSchedule.MANUAL,
                refreshWifiOnly = preferenceSnapshot.refreshWifiOnly[source.sourceId] ?: false,
                liveOrganizationMode = LiveOrganizationMode.PROVIDER,
            )
        }
        val payload = room.toPayload(
            activeSourceId = activeSourceId,
            globalSettings = preferenceSnapshot.globalSettings,
            sourceSettings = sourceSettings,
        )
        val bytes = codec.encode(
            OwnPlayBackupEnvelope(createdAt = now().toString(), payload = payload),
        )
        BackupExportResult.Success(bytes)
    } catch (_: Exception) {
        BackupExportResult.Failure
    }

    override suspend fun previewRestore(bytes: ByteArray): BackupRestorePreview {
        val decoded = codec.decode(bytes)
        if (decoded is BackupJsonDecodeResult.Failure) {
            return BackupRestorePreview.Rejected(decoded.issues)
        }
        val envelope = (decoded as BackupJsonDecodeResult.Success).envelope
        val existing = runCatching { existingSources() }.getOrElse {
            return BackupRestorePreview.StorageFailure
        }
        val plan = BackupRestorePlanner.plan(envelope.payload, existing)
        return if (plan.hasConflicts) {
            BackupRestorePreview.Conflicted(plan)
        } else {
            BackupRestorePreview.Ready(plan)
        }
    }

    override suspend fun restore(bytes: ByteArray): BackupRestoreResult {
        val decoded = codec.decode(bytes)
        if (decoded is BackupJsonDecodeResult.Failure) {
            return BackupRestoreResult.Rejected(decoded.issues)
        }
        val payload = (decoded as BackupJsonDecodeResult.Success).envelope.payload
        val existingRows = runCatching { sourceDao.getAll() }.getOrElse {
            return BackupRestoreResult.StorageFailure
        }
        val plan = BackupRestorePlanner.plan(payload, existingRows.map { it.toExistingBackupSource() })
        if (plan.hasConflicts) return BackupRestoreResult.Conflicted(plan)
        return applyRestore(payload, plan, existingRows)
    }

    private suspend fun applyRestore(
        payload: OwnPlayBackupPayload,
        plan: BackupRestorePlan,
        existingRows: List<SourceEntity>,
    ): BackupRestoreResult {
        val mapping = plan.sourceResolutions.associate { it.backupSourceId to requireNotNull(it.targetSourceId) }
        val backupSources = payload.sources.associateBy(BackupSourceDefinition::sourceId)
        val existingById = existingRows.associateBy(SourceEntity::sourceId)
        val timestamp = now().toEpochMilli()
        val targetRows = plan.sourceResolutions.map { resolution ->
            val backup = backupSources.getValue(resolution.backupSourceId)
            when (resolution.action) {
                BackupSourceRestoreAction.MERGE_EXISTING -> {
                    existingById.getValue(requireNotNull(resolution.targetSourceId))
                }
                BackupSourceRestoreAction.CREATE_DISABLED -> SourceEntity(
                    sourceId = requireNotNull(resolution.targetSourceId),
                    displayName = backup.displayName,
                    type = backup.type.name,
                    baseLocator = backup.baseLocator,
                    credentialReference = null,
                    enabled = false,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                )
                BackupSourceRestoreAction.CONFLICT -> error("Conflicted restore plan cannot be applied")
            }
        }
        val targetById = targetRows.associateBy(SourceEntity::sourceId)
        val refreshSchedules = payload.sourceSettings.associate { setting ->
            mapping.getValue(setting.sourceId) to setting.refreshSchedule
        }
        val refreshWifiOnly = payload.sourceSettings.associate { setting ->
            mapping.getValue(setting.sourceId) to setting.refreshWifiOnly
        }
        val preferenceSnapshot = runCatching { preferences.snapshot(mapping.values) }.getOrElse {
            return BackupRestoreResult.StorageFailure
        }
        val enabledAfterRestore = buildList {
            existingRows.forEach { row ->
                if (targetById[row.sourceId] == null && row.enabled) add(row.sourceId)
            }
            targetRows.forEach { row ->
                if (row.enabled) add(row.sourceId)
            }
        }.distinct()
        val intendedTarget = payload.activeSourceId?.let(mapping::get)
        val intendedActiveTarget = intendedTarget?.takeIf { it !in enabledAfterRestore }
        val activeTarget = when {
            intendedTarget == null -> null
            intendedTarget in enabledAfterRestore -> intendedTarget
            preferenceSnapshot.activeSourceId?.let { it in enabledAfterRestore } == true ->
                preferenceSnapshot.activeSourceId
            else -> enabledAfterRestore.firstOrNull()
        }
        val prepared = runCatching { prepareRestoreRows(payload, mapping) }.getOrElse {
            return BackupRestoreResult.StorageFailure
        }

        val applied = runCatching {
            database.withTransaction {
                plan.sourceResolutions.zip(targetRows).forEach { (resolution, row) ->
                    when (resolution.action) {
                        BackupSourceRestoreAction.MERGE_EXISTING -> sourceDao.update(row)
                        BackupSourceRestoreAction.CREATE_DISABLED -> sourceDao.insert(row)
                        BackupSourceRestoreAction.CONFLICT -> error("Conflict")
                    }
                }
                prepared.apply(backupDao)
                preferences.apply(
                    globalSettings = payload.globalSettings,
                    activeSourceId = activeTarget,
                    intendedActiveSourceId = intendedActiveTarget,
                    refreshSchedules = refreshSchedules,
                    refreshWifiOnly = refreshWifiOnly,
                )
            }
        }
        if (applied.isFailure) {
            runCatching { preferences.restore(preferenceSnapshot) }
            return BackupRestoreResult.StorageFailure
        }

        val enabledSourceIds = targetRows.filter(SourceEntity::enabled).mapTo(mutableSetOf(), SourceEntity::sourceId)
        val scheduleFailures = preferences.syncRefreshSchedules(
            schedules = refreshSchedules,
            refreshWifiOnly = refreshWifiOnly,
            enabledSourceIds = enabledSourceIds,
        )
        return BackupRestoreResult.Success(
            BackupRestoreReport(
                mergedSources = plan.sourceResolutions.count { it.action == BackupSourceRestoreAction.MERGE_EXISTING },
                createdDisabledSources = plan.sourceResolutions.count {
                    it.action == BackupSourceRestoreAction.CREATE_DISABLED
                },
                skippedCategoryPersonalization = prepared.skippedCategoryPersonalization,
                skippedChannelPersonalization = prepared.skippedChannelPersonalization,
                skippedMediaFavorites = prepared.skippedMediaFavorites,
                skippedCustomGroups = prepared.skippedCustomGroups,
                skippedLivePlacements = prepared.skippedLivePlacements,
                skippedCustomGroupMemberships = prepared.skippedCustomGroupMemberships,
                refreshScheduleSyncFailures = scheduleFailures,
            ),
        )
    }

    private suspend fun existingSources(): List<ExistingBackupSource> =
        sourceDao.getAll().map { it.toExistingBackupSource() }

    private fun SourceEntity.toExistingBackupSource() = ExistingBackupSource(
        sourceId = sourceId,
        type = SourceType.valueOf(type),
        baseLocator = baseLocator,
    )

    private suspend fun prepareRestoreRows(
        payload: OwnPlayBackupPayload,
        mapping: Map<String, String>,
    ): PreparedRestoreRows {
        val targetSourceIds = mapping.values.toSet()
        val channelIdsBySource = targetSourceIds.associateWith { sourceId ->
            backupDao.getChannelIds(sourceId).toSet()
        }
        val providerCategoryKeysBySource = targetSourceIds.associateWith { sourceId ->
            BackupCatalogKind.entries.associateWith { kind ->
                backupDao.getProviderCategoryKeys(sourceId, kind.name).toSet()
            }
        }
        val movieIdsBySource = targetSourceIds.associateWith { sourceId ->
            backupDao.getMovieIds(sourceId).toSet()
        }
        val seriesIdsBySource = targetSourceIds.associateWith { sourceId ->
            backupDao.getSeriesIds(sourceId).toSet()
        }

        val validCategoryPersonalization = payload.categoryPersonalization.filter { row ->
            val targetSourceId = mapping.getValue(row.sourceId)
            row.categoryKey in providerCategoryKeysBySource
                .getValue(targetSourceId)
                .getValue(row.kind)
        }
        val categoryRows = validCategoryPersonalization.map { row ->
            CategoryPersonalizationEntity(
                sourceId = mapping.getValue(row.sourceId),
                kind = row.kind.name,
                categoryKey = row.categoryKey,
                hidden = row.hidden,
                manualOrder = row.manualOrder,
            )
        }
        val validChannelRows = payload.channelPersonalization.filter { row ->
            row.channelId in channelIdsBySource.getValue(mapping.getValue(row.sourceId))
        }
        val channelRows = validChannelRows.map { row ->
            ChannelPersonalizationEntity(
                channelId = row.channelId,
                favorite = row.favorite,
                hidden = row.hidden,
                localName = row.localName,
                localLogo = row.localLogo,
                manualOrder = row.manualOrder,
            )
        }
        val validMediaFavorites = payload.mediaFavorites.filter { row ->
            val targetSourceId = mapping.getValue(row.sourceId)
            when (row.mediaKind) {
                BackupMediaKind.MOVIE -> row.contentId in movieIdsBySource.getValue(targetSourceId)
                BackupMediaKind.SERIES -> row.contentId in seriesIdsBySource.getValue(targetSourceId)
            }
        }
        val favoriteRows = validMediaFavorites.map { row ->
            MediaFavoriteEntity(
                sourceId = mapping.getValue(row.sourceId),
                mediaKind = row.mediaKind.name,
                contentId = row.contentId,
                addedAt = row.addedAt,
            )
        }
        val livePreferenceRows = payload.sourceSettings.map { row ->
            LiveOrganizationPreferenceEntity(
                sourceId = mapping.getValue(row.sourceId),
                activeMode = LiveOrganizationMode.PROVIDER.name,
            )
        }
        val validLiveCategoryPersonalization = payload.liveCategoryPersonalization.filter { row ->
            val targetSourceId = mapping.getValue(row.sourceId)
            row.organizationMode == LiveOrganizationMode.PROVIDER &&
                (
                    row.categoryId == ProviderLiveOrganizationContract.UNCATEGORIZED_CATEGORY_ID ||
                        row.categoryId in providerCategoryKeysBySource
                            .getValue(targetSourceId)
                            .getValue(BackupCatalogKind.LIVE)
                    )
        }
        val liveCategoryRows = validLiveCategoryPersonalization.map { row ->
            LiveCategoryScopePersonalizationEntity(
                sourceId = mapping.getValue(row.sourceId),
                organizationMode = LiveOrganizationMode.PROVIDER.name,
                categoryId = row.categoryId,
                hidden = row.hidden,
                manualOrder = row.manualOrder,
            )
        }
        val placementRows = emptyList<LiveChannelMembershipPersonalizationEntity>()
        val acceptedGroups = mutableListOf<BackupCustomGroup>()
        var skippedGroups = 0
        payload.customGroups.forEach { row ->
            val targetSourceId = mapping.getValue(row.sourceId)
            val existingSourceId = backupDao.getGroupSourceId(row.groupId)
            if (existingSourceId == null || existingSourceId == targetSourceId) {
                acceptedGroups += row
            } else {
                skippedGroups += 1
            }
        }
        val groupSourceById = acceptedGroups.associate { it.groupId to mapping.getValue(it.sourceId) }
        val groupRows = acceptedGroups.map { row ->
            CustomGroupEntity(
                groupId = row.groupId,
                sourceId = mapping.getValue(row.sourceId),
                name = row.name,
                manualOrder = row.manualOrder,
            )
        }
        val validMemberships = payload.customGroupMemberships.filter { row ->
            val sourceId = groupSourceById[row.groupId] ?: return@filter false
            row.channelId in channelIdsBySource.getValue(sourceId)
        }
        val membershipRows = validMemberships.map { row ->
            CustomGroupMembershipEntity(
                groupId = row.groupId,
                channelId = row.channelId,
                manualOrder = row.manualOrder,
            )
        }
        return PreparedRestoreRows(
            categoryPersonalization = categoryRows,
            channelPersonalization = channelRows,
            mediaFavorites = favoriteRows,
            livePreferences = livePreferenceRows,
            liveCategoryPersonalization = liveCategoryRows,
            livePlacementOverrides = placementRows,
            customGroups = groupRows,
            customGroupMemberships = membershipRows,
            skippedCategoryPersonalization =
                (payload.categoryPersonalization.size - validCategoryPersonalization.size) +
                    (payload.liveCategoryPersonalization.size - validLiveCategoryPersonalization.size),
            skippedChannelPersonalization = payload.channelPersonalization.size - validChannelRows.size,
            skippedMediaFavorites = payload.mediaFavorites.size - validMediaFavorites.size,
            skippedCustomGroups = skippedGroups,
            skippedLivePlacements = payload.livePlacementOverrides.size,
            skippedCustomGroupMemberships = payload.customGroupMemberships.size - validMemberships.size,
        )
    }

    private data class PreparedRestoreRows(
        val categoryPersonalization: List<CategoryPersonalizationEntity>,
        val channelPersonalization: List<ChannelPersonalizationEntity>,
        val mediaFavorites: List<MediaFavoriteEntity>,
        val livePreferences: List<LiveOrganizationPreferenceEntity>,
        val liveCategoryPersonalization: List<LiveCategoryScopePersonalizationEntity>,
        val livePlacementOverrides: List<LiveChannelMembershipPersonalizationEntity>,
        val customGroups: List<CustomGroupEntity>,
        val customGroupMemberships: List<CustomGroupMembershipEntity>,
        val skippedCategoryPersonalization: Int,
        val skippedChannelPersonalization: Int,
        val skippedMediaFavorites: Int,
        val skippedCustomGroups: Int,
        val skippedLivePlacements: Int,
        val skippedCustomGroupMemberships: Int,
    ) {
        suspend fun apply(dao: BackupDao) {
            if (categoryPersonalization.isNotEmpty()) dao.upsertCategoryPersonalization(categoryPersonalization)
            if (channelPersonalization.isNotEmpty()) dao.upsertChannelPersonalization(channelPersonalization)
            if (mediaFavorites.isNotEmpty()) dao.upsertMediaFavorites(mediaFavorites)
            if (livePreferences.isNotEmpty()) dao.upsertLivePreferences(livePreferences)
            if (liveCategoryPersonalization.isNotEmpty()) {
                dao.upsertLiveCategoryPersonalization(liveCategoryPersonalization)
            }
            if (livePlacementOverrides.isNotEmpty()) dao.upsertManualPlacementOverrides(livePlacementOverrides)
            if (customGroups.isNotEmpty()) dao.upsertCustomGroups(customGroups)
            if (customGroupMemberships.isNotEmpty()) dao.upsertCustomGroupMemberships(customGroupMemberships)
        }
    }

    private suspend fun captureRoomSnapshot(): RoomBackupSnapshot = RoomBackupSnapshot(
        sources = sourceDao.getAll(),
        categoryPersonalization = backupDao.getCategoryPersonalization(),
        channelPersonalization = backupDao.getChannelPersonalization(),
        mediaFavorites = backupDao.getMediaFavorites(),
        liveCategoryPersonalization = backupDao.getLiveCategoryPersonalization(),
        customGroups = backupDao.getCustomGroups(),
        customGroupMemberships = backupDao.getCustomGroupMemberships(),
    )

    private data class RoomBackupSnapshot(
        val sources: List<SourceEntity>,
        val categoryPersonalization: List<CategoryPersonalizationEntity>,
        val channelPersonalization: List<app.ownplay.mobile.data.db.BackupChannelPersonalizationRow>,
        val mediaFavorites: List<MediaFavoriteEntity>,
        val liveCategoryPersonalization: List<LiveCategoryScopePersonalizationEntity>,
        val customGroups: List<CustomGroupEntity>,
        val customGroupMemberships: List<CustomGroupMembershipEntity>,
    )

    private fun RoomBackupSnapshot.toPayload(
        activeSourceId: String?,
        globalSettings: BackupGlobalSettings,
        sourceSettings: List<BackupSourceSettings>,
    ): OwnPlayBackupPayload = OwnPlayBackupPayload(
        sources = sources.map { row ->
            BackupSourceDefinition(
                sourceId = row.sourceId,
                type = SourceType.valueOf(row.type),
                displayName = row.displayName,
                baseLocator = row.baseLocator,
                enabled = row.enabled,
            )
        },
        activeSourceId = activeSourceId,
        globalSettings = globalSettings,
        sourceSettings = sourceSettings,
        categoryPersonalization = categoryPersonalization.map { row ->
            BackupCategoryPersonalization(
                sourceId = row.sourceId,
                kind = BackupCatalogKind.valueOf(row.kind),
                categoryKey = row.categoryKey,
                hidden = row.hidden,
                manualOrder = row.manualOrder,
            )
        },
        channelPersonalization = channelPersonalization.map { row ->
            BackupChannelPersonalization(
                sourceId = row.sourceId,
                channelId = row.channelId,
                favorite = row.favorite,
                hidden = row.hidden,
                localName = row.localName,
                localLogo = row.localLogo,
                manualOrder = row.manualOrder,
            )
        },
        mediaFavorites = mediaFavorites.map { row ->
            BackupMediaFavorite(
                sourceId = row.sourceId,
                mediaKind = BackupMediaKind.valueOf(row.mediaKind),
                contentId = row.contentId,
                addedAt = row.addedAt,
            )
        },
        liveCategoryPersonalization = liveCategoryPersonalization
            .filter { row -> row.organizationMode == LiveOrganizationMode.PROVIDER.name }
            .map { row ->
                BackupLiveCategoryPersonalization(
                    sourceId = row.sourceId,
                    organizationMode = LiveOrganizationMode.PROVIDER,
                    categoryId = row.categoryId,
                    hidden = row.hidden,
                    manualOrder = row.manualOrder,
                )
            },
        livePlacementOverrides = emptyList(),
        customGroups = customGroups.map { row ->
            BackupCustomGroup(
                sourceId = row.sourceId,
                groupId = row.groupId,
                name = row.name,
                manualOrder = row.manualOrder,
            )
        },
        customGroupMemberships = customGroupMemberships.map { row ->
            BackupCustomGroupMembership(
                groupId = row.groupId,
                channelId = row.channelId,
                manualOrder = row.manualOrder,
            )
        },
    )
}
