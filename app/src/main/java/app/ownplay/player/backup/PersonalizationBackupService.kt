package app.ownplay.player.backup

import android.content.Context
import androidx.room.withTransaction
import app.ownplay.player.persistence.ChannelCustomizationEntity
import app.ownplay.player.persistence.CustomGroupEntity
import app.ownplay.player.persistence.CustomGroupMembershipEntity
import app.ownplay.player.persistence.FavoriteEntryEntity
import app.ownplay.player.persistence.HiddenEntryEntity
import app.ownplay.player.persistence.OwnPlayDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface BackupExportResult {
    data class Success(
        val content: String,
        val channelRecords: Int,
        val groups: Int,
        val memberships: Int,
        val omittedLogoOverrides: Int,
    ) : BackupExportResult

    data object Failure : BackupExportResult
}

enum class BackupRestoreFailureReason {
    INVALID_JSON,
    UNSUPPORTED_FORMAT,
    UNSUPPORTED_VERSION,
    INVALID_PAYLOAD,
    PERSISTENCE_FAILURE,
}

sealed interface BackupRestoreResult {
    data class Success(
        val appliedChannelRecords: Int,
        val appliedGroups: Int,
        val appliedMemberships: Int,
        val unmatchedChannelIdentities: Int,
        val ambiguousChannelIdentities: Int,
        val omittedLogoOverrides: Int,
    ) : BackupRestoreResult

    data class Failure(val reason: BackupRestoreFailureReason) : BackupRestoreResult
}

class PersonalizationBackupService(
    context: Context,
) {
    private val applicationContext = context.applicationContext

    suspend fun exportBackup(): BackupExportResult = withContext(Dispatchers.IO) {
        val database = OwnPlayDatabase.create(applicationContext)
        try {
            val sourceDao = database.playlistSourceDao()
            val catalogDao = database.providerCatalogDao()
            val personalizationDao = database.personalizationDao()
            val identitiesByChannelId = linkedMapOf<String, BackupChannelIdentity>()
            val channelRecords = mutableListOf<BackupChannelRecord>()

            sourceDao.allForBackup().forEach { source ->
                val channels = catalogDao.channelsForSource(source.sourceId)
                val channelsById = channels.associateBy { channel -> channel.channelId }
                channels.forEach { channel ->
                    identitiesByChannelId[channel.channelId] = BackupChannelIdentity(
                        providerKey = channel.providerKey,
                        sourceKind = source.sourceKind,
                        sourceName = source.name,
                        sourceId = source.sourceId,
                    )
                }

                val customizations = personalizationDao.customizationsForSource(source.sourceId)
                    .associateBy { customization -> customization.channelId }
                val hidden = personalizationDao.hiddenEntriesForSource(source.sourceId)
                    .associateBy { entry -> entry.channelId }
                val favorites = personalizationDao.favoriteEntriesForSource(source.sourceId)
                    .associateBy { entry -> entry.channelId }
                val personalizedIds = (customizations.keys + hidden.keys + favorites.keys)
                    .sortedWith(compareBy({ identitiesByChannelId[it]?.providerKey.orEmpty() }, { it }))

                personalizedIds.forEach { channelId ->
                    val channel = channelsById[channelId] ?: return@forEach
                    val identity = identitiesByChannelId[channel.channelId] ?: return@forEach
                    val customization = customizations[channel.channelId]
                    val favorite = favorites[channel.channelId]
                    channelRecords += BackupChannelRecord(
                        identity = identity,
                        localDisplayName = customization?.localDisplayName,
                        manualOrder = customization?.manualOrder,
                        hiddenAtEpochMillis = hidden[channel.channelId]?.hiddenAtEpochMillis,
                        favoriteOrder = favorite?.favoriteOrder,
                        favoriteAddedAtEpochMillis = favorite?.addedAtEpochMillis,
                        logoOverrideOmitted = customization?.logoOverrideRef != null,
                    )
                }
            }

            val groups = personalizationDao.customGroupsForMutation().map { group ->
                val members = personalizationDao.groupMemberships(group.groupId)
                    .mapNotNull { membership ->
                        val identity = identitiesByChannelId[membership.channelId]
                            ?: return@mapNotNull null
                        BackupGroupMember(
                            identity = identity,
                            groupOrder = membership.groupOrder,
                        )
                    }
                BackupGroupRecord(
                    groupId = group.groupId,
                    name = group.name,
                    groupOrder = group.groupOrder,
                    createdAtEpochMillis = group.createdAtEpochMillis,
                    members = members,
                )
            }
            val backup = PersonalizationBackupV1(
                createdAtEpochMillis = System.currentTimeMillis(),
                channels = channelRecords,
                groups = groups,
            )
            BackupExportResult.Success(
                content = PersonalizationBackupCodec.encode(backup),
                channelRecords = channelRecords.size,
                groups = groups.size,
                memberships = groups.sumOf { group -> group.members.size },
                omittedLogoOverrides = channelRecords.count(BackupChannelRecord::logoOverrideOmitted),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            BackupExportResult.Failure
        } finally {
            database.close()
        }
    }

    suspend fun restoreBackup(raw: String): BackupRestoreResult = withContext(Dispatchers.IO) {
        val backup = when (val decoded = PersonalizationBackupCodec.decode(raw)) {
            is BackupDecodeResult.Success -> decoded.backup
            is BackupDecodeResult.Failure -> {
                return@withContext BackupRestoreResult.Failure(decoded.reason.toRestoreFailure())
            }
        }

        val database = OwnPlayDatabase.create(applicationContext)
        try {
            val sourceDao = database.playlistSourceDao()
            val catalogDao = database.providerCatalogDao()
            val sources = sourceDao.allForBackup()
            val candidates = sources.flatMap { source ->
                catalogDao.channelsForSource(source.sourceId).map { channel ->
                    RestoreChannelCandidate(
                        channelId = channel.channelId,
                        sourceId = source.sourceId,
                        providerKey = channel.providerKey,
                        sourceKind = source.sourceKind,
                        sourceName = source.name,
                    )
                }
            }
            val candidatesByProviderKey = candidates.groupBy(RestoreChannelCandidate::providerKey)
            val matches = mutableMapOf<String, BackupChannelMatch>()
            val unmatched = linkedSetOf<String>()
            val ambiguous = linkedSetOf<String>()

            fun resolve(identity: BackupChannelIdentity): BackupChannelMatch {
                val key = identity.stableKey()
                return matches.getOrPut(key) {
                    BackupChannelMatcher.resolve(
                        identity = identity,
                        candidates = candidatesByProviderKey[identity.providerKey].orEmpty(),
                    ).also { match ->
                        when (match) {
                            BackupChannelMatch.Unmatched -> unmatched += key
                            BackupChannelMatch.Ambiguous -> ambiguous += key
                            is BackupChannelMatch.Matched -> Unit
                        }
                    }
                }
            }

            val channelPlans = backup.channels.mapNotNull { record ->
                val match = resolve(record.identity)
                val candidate = (match as? BackupChannelMatch.Matched)?.candidate
                    ?: return@mapNotNull null
                if (!record.hasRestorableChannelState()) return@mapNotNull null
                ResolvedChannelRecord(record, candidate)
            }
            val membershipPlans = backup.groups.flatMap { group ->
                group.members.mapNotNull { member ->
                    val match = resolve(member.identity)
                    val candidate = (match as? BackupChannelMatch.Matched)?.candidate
                        ?: return@mapNotNull null
                    ResolvedGroupMember(group.groupId, member.groupOrder, candidate.channelId)
                }
            }

            database.withTransaction {
                val personalizationDao = database.personalizationDao()
                channelPlans.forEach { plan ->
                    val record = plan.record
                    val candidate = plan.candidate
                    if (record.localDisplayName != null || record.manualOrder != null) {
                        val existing = personalizationDao.customizationForChannel(
                            candidate.sourceId,
                            candidate.channelId,
                        )
                        val base = existing ?: ChannelCustomizationEntity(channelId = candidate.channelId)
                        personalizationDao.upsertCustomization(
                            base.copy(
                                localDisplayName = record.localDisplayName ?: base.localDisplayName,
                                manualOrder = record.manualOrder ?: base.manualOrder,
                            ),
                        )
                    }
                    record.hiddenAtEpochMillis?.let { hiddenAt ->
                        personalizationDao.upsertHidden(
                            HiddenEntryEntity(
                                channelId = candidate.channelId,
                                hiddenAtEpochMillis = hiddenAt,
                            ),
                        )
                    }
                    if (record.favoriteOrder != null && record.favoriteAddedAtEpochMillis != null) {
                        personalizationDao.upsertFavorite(
                            FavoriteEntryEntity(
                                channelId = candidate.channelId,
                                favoriteOrder = record.favoriteOrder,
                                addedAtEpochMillis = record.favoriteAddedAtEpochMillis,
                            ),
                        )
                    }
                }
                backup.groups.forEach { group ->
                    personalizationDao.upsertGroup(
                        CustomGroupEntity(
                            groupId = group.groupId,
                            name = group.name,
                            groupOrder = group.groupOrder,
                            createdAtEpochMillis = group.createdAtEpochMillis,
                        ),
                    )
                }
                membershipPlans.forEach { plan ->
                    personalizationDao.upsertGroupMembership(
                        CustomGroupMembershipEntity(
                            groupId = plan.groupId,
                            channelId = plan.channelId,
                            groupOrder = plan.groupOrder,
                        ),
                    )
                }
            }

            BackupRestoreResult.Success(
                appliedChannelRecords = channelPlans.size,
                appliedGroups = backup.groups.size,
                appliedMemberships = membershipPlans.size,
                unmatchedChannelIdentities = unmatched.size,
                ambiguousChannelIdentities = ambiguous.size,
                omittedLogoOverrides = backup.channels.count(BackupChannelRecord::logoOverrideOmitted),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            BackupRestoreResult.Failure(BackupRestoreFailureReason.PERSISTENCE_FAILURE)
        } finally {
            database.close()
        }
    }
}

private data class ResolvedChannelRecord(
    val record: BackupChannelRecord,
    val candidate: RestoreChannelCandidate,
)

private data class ResolvedGroupMember(
    val groupId: String,
    val groupOrder: Long,
    val channelId: String,
)

private fun BackupChannelRecord.hasRestorableChannelState(): Boolean =
    localDisplayName != null || manualOrder != null || hiddenAtEpochMillis != null || favoriteOrder != null

private fun BackupDecodeFailureReason.toRestoreFailure(): BackupRestoreFailureReason = when (this) {
    BackupDecodeFailureReason.INVALID_JSON -> BackupRestoreFailureReason.INVALID_JSON
    BackupDecodeFailureReason.UNSUPPORTED_FORMAT -> BackupRestoreFailureReason.UNSUPPORTED_FORMAT
    BackupDecodeFailureReason.UNSUPPORTED_VERSION -> BackupRestoreFailureReason.UNSUPPORTED_VERSION
    BackupDecodeFailureReason.INVALID_PAYLOAD -> BackupRestoreFailureReason.INVALID_PAYLOAD
}
