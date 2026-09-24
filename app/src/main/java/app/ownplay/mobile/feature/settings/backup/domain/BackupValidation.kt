package app.ownplay.mobile.feature.settings.backup.domain

import app.ownplay.mobile.downloads.domain.DownloadDestinationPolicy
import java.net.URI
import java.time.Instant

enum class BackupValidationCode {
    INVALID_PACKAGE,
    INVALID_BACKUP_FILE,
    FORBIDDEN_SECRET_FIELD,
    WRONG_FORMAT,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_NEWER_VERSION,
    INVALID_CREATED_AT,
    INVALID_FIELD,
    DUPLICATE_IDENTITY,
    INVALID_REFERENCE,
}

data class BackupValidationIssue(
    val code: BackupValidationCode,
    val path: String,
)

object BackupValidator {
    fun validate(envelope: OwnPlayBackupEnvelope): List<BackupValidationIssue> {
        val issues = mutableListOf<BackupValidationIssue>()
        if (envelope.format != BackupFormatContract.FORMAT) {
            issues += issue(BackupValidationCode.WRONG_FORMAT, "$.format")
        }
        if (envelope.version > BackupFormatContract.VERSION) {
            issues += issue(BackupValidationCode.UNSUPPORTED_NEWER_VERSION, "$.version")
        } else if (envelope.version != BackupFormatContract.VERSION) {
            issues += issue(BackupValidationCode.UNSUPPORTED_VERSION, "$.version")
        }
        if (runCatching { Instant.parse(envelope.createdAt) }.isFailure) {
            issues += issue(BackupValidationCode.INVALID_CREATED_AT, "$.createdAt")
        }

        val sources = envelope.payload.sources
        val sourceIds = sources.map(BackupSourceDefinition::sourceId)
        if (sourceIds.any(String::isBlank)) {
            issues += issue(BackupValidationCode.INVALID_FIELD, "$.payload.sources[].sourceId")
        }
        if (sourceIds.size != sourceIds.toSet().size) {
            issues += issue(BackupValidationCode.DUPLICATE_IDENTITY, "$.payload.sources[].sourceId")
        }
        val sourceConnections = sources.map { it.type to it.baseLocator }
        if (sourceConnections.size != sourceConnections.toSet().size) {
            issues += issue(BackupValidationCode.DUPLICATE_IDENTITY, "$.payload.sources[].baseLocator")
        }
        sources.forEachIndexed { index, source ->
            if (source.displayName.isBlank() || !isSafeLocator(source.baseLocator)) {
                issues += issue(BackupValidationCode.INVALID_FIELD, "$.payload.sources[$index]")
            }
        }

        val settings = envelope.payload.globalSettings
        if (settings.playback.playerVolume !in 0f..1f) {
            issues += issue(BackupValidationCode.INVALID_FIELD, "$.payload.globalSettings.playback.playerVolume")
        }
        if (DownloadDestinationPolicy.normalize(settings.downloads.destinationRelativePath) == null) {
            issues += issue(
                BackupValidationCode.INVALID_FIELD,
                "$.payload.globalSettings.downloads.destinationRelativePath",
            )
        }

        val sourceSet = sourceIds.toSet()
        envelope.payload.activeSourceId?.let { activeSourceId ->
            if (activeSourceId !in sourceSet) {
                issues += issue(BackupValidationCode.INVALID_REFERENCE, "$.payload.activeSourceId")
            }
        }
        validateSourceScopedRows(envelope.payload, sourceSet, issues)
        return issues
    }

    private fun validateSourceScopedRows(
        payload: OwnPlayBackupPayload,
        sourceIds: Set<String>,
        issues: MutableList<BackupValidationIssue>,
    ) {
        fun references(path: String, values: List<String>) {
            if (values.any { it !in sourceIds }) {
                issues += issue(BackupValidationCode.INVALID_REFERENCE, path)
            }
        }
        fun unique(path: String, values: List<String>) {
            if (values.size != values.toSet().size) {
                issues += issue(BackupValidationCode.DUPLICATE_IDENTITY, path)
            }
        }

        if (payload.categoryPersonalization.any { it.categoryKey.isBlank() }) {
            issues += issue(BackupValidationCode.INVALID_FIELD, "$.payload.categoryPersonalization[].categoryKey")
        }
        if (payload.channelPersonalization.any { it.channelId.isBlank() }) {
            issues += issue(BackupValidationCode.INVALID_FIELD, "$.payload.channelPersonalization[].channelId")
        }
        if (payload.mediaFavorites.any { it.contentId.isBlank() }) {
            issues += issue(BackupValidationCode.INVALID_FIELD, "$.payload.mediaFavorites[].contentId")
        }
        if (payload.liveCategoryPersonalization.any { it.categoryId.isBlank() }) {
            issues += issue(BackupValidationCode.INVALID_FIELD, "$.payload.liveCategoryPersonalization[].categoryId")
        }
        if (payload.livePlacementOverrides.any { it.categoryId.isBlank() || it.channelId.isBlank() }) {
            issues += issue(BackupValidationCode.INVALID_FIELD, "$.payload.livePlacementOverrides[]")
        }
        if (payload.customGroups.any { it.groupId.isBlank() || it.name.isBlank() }) {
            issues += issue(BackupValidationCode.INVALID_FIELD, "$.payload.customGroups[]")
        }
        if (payload.customGroupMemberships.any { it.groupId.isBlank() || it.channelId.isBlank() }) {
            issues += issue(BackupValidationCode.INVALID_FIELD, "$.payload.customGroupMemberships[]")
        }

        references("$.payload.sourceSettings", payload.sourceSettings.map { it.sourceId })
        unique("$.payload.sourceSettings", payload.sourceSettings.map { it.sourceId })
        references("$.payload.categoryPersonalization", payload.categoryPersonalization.map { it.sourceId })
        unique(
            "$.payload.categoryPersonalization",
            payload.categoryPersonalization.map { "${it.sourceId}|${it.kind}|${it.categoryKey}" },
        )
        references("$.payload.channelPersonalization", payload.channelPersonalization.map { it.sourceId })
        unique(
            "$.payload.channelPersonalization",
            payload.channelPersonalization.map { "${it.sourceId}|${it.channelId}" },
        )
        references("$.payload.mediaFavorites", payload.mediaFavorites.map { it.sourceId })
        unique(
            "$.payload.mediaFavorites",
            payload.mediaFavorites.map { "${it.sourceId}|${it.mediaKind}|${it.contentId}" },
        )
        references(
            "$.payload.liveCategoryPersonalization",
            payload.liveCategoryPersonalization.map { it.sourceId },
        )
        unique(
            "$.payload.liveCategoryPersonalization",
            payload.liveCategoryPersonalization.map {
                "${it.sourceId}|${it.organizationMode}|${it.categoryId}"
            },
        )
        references("$.payload.livePlacementOverrides", payload.livePlacementOverrides.map { it.sourceId })
        unique(
            "$.payload.livePlacementOverrides",
            payload.livePlacementOverrides.map { "${it.sourceId}|${it.categoryId}|${it.channelId}" },
        )
        references("$.payload.customGroups", payload.customGroups.map { it.sourceId })
        unique("$.payload.customGroups", payload.customGroups.map { it.groupId })

        val groupIds = payload.customGroups.mapTo(mutableSetOf(), BackupCustomGroup::groupId)
        if (payload.customGroupMemberships.any { it.groupId !in groupIds }) {
            issues += issue(
                BackupValidationCode.INVALID_REFERENCE,
                "$.payload.customGroupMemberships[].groupId",
            )
        }
        unique(
            "$.payload.customGroupMemberships",
            payload.customGroupMemberships.map { "${it.groupId}|${it.channelId}" },
        )
    }

    private fun isSafeLocator(raw: String): Boolean {
        if (raw.isBlank()) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null
    }

    private fun issue(
        code: BackupValidationCode,
        path: String,
    ): BackupValidationIssue = BackupValidationIssue(code, path)
}
