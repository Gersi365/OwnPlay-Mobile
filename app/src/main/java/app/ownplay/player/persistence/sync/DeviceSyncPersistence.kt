package app.ownplay.player.persistence.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert

internal const val DEVICE_SYNC_LOCAL_STATE_KEY = "local"

/**
 * Persistent identity and monotonic local revision source for future cross-device sync.
 *
 * This state contains no account/authentication data and no remote transport configuration.
 */
@Entity(
    tableName = "device_sync_local_state",
    indices = [Index(value = ["deviceId"], unique = true)],
)
data class DeviceSyncLocalStateEntity(
    @androidx.room.PrimaryKey val stateKey: String = DEVICE_SYNC_LOCAL_STATE_KEY,
    val deviceId: String,
    val nextRevision: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(stateKey.isNotBlank())
        require(deviceId.isNotBlank())
        require(nextRevision >= 0L)
        require(updatedAtEpochMillis >= 0L)
    }
}

/**
 * Sync identity for a playlist source. There is deliberately no foreign key to playlist_sources:
 * a deleted local source must keep its sync tombstone so an older device cannot resurrect it.
 *
 * encryptedSecretRef is only a future reference to an encrypted portable envelope. Local Android
 * Keystore references must never be copied into this column as if they were portable secrets.
 */
@Entity(
    tableName = "device_sync_sources",
    indices = [
        Index(value = ["localSourceId"], unique = true),
        Index(value = ["sourceKind"]),
    ],
)
data class DeviceSyncSourceEntity(
    @androidx.room.PrimaryKey val syncSourceId: String,
    val localSourceId: String?,
    val sourceKind: String,
    val locatorFingerprint: String?,
    val displayName: String,
    val displayNameUpdatedAtEpochMillis: Long,
    val displayNameRevision: Long,
    val displayNameDeviceId: String,
    val enabled: Boolean,
    val enabledUpdatedAtEpochMillis: Long,
    val enabledRevision: Long,
    val enabledDeviceId: String,
    val deleted: Boolean,
    val deletedUpdatedAtEpochMillis: Long,
    val deletedRevision: Long,
    val deletedDeviceId: String,
    val encryptedSecretRef: String? = null,
    val encryptedSecretUpdatedAtEpochMillis: Long? = null,
    val encryptedSecretRevision: Long? = null,
    val encryptedSecretDeviceId: String? = null,
) {
    init {
        require(syncSourceId.isNotBlank())
        localSourceId?.let { require(it.isNotBlank()) }
        require(sourceKind.isNotBlank())
        locatorFingerprint?.let { require(it.isNotBlank()) }
        require(displayName.isNotBlank())
        requireClock(displayNameUpdatedAtEpochMillis, displayNameRevision, displayNameDeviceId)
        requireClock(enabledUpdatedAtEpochMillis, enabledRevision, enabledDeviceId)
        requireClock(deletedUpdatedAtEpochMillis, deletedRevision, deletedDeviceId)
        requireOptionalClock(
            encryptedSecretUpdatedAtEpochMillis,
            encryptedSecretRevision,
            encryptedSecretDeviceId,
        )
        encryptedSecretRef?.let { value ->
            require(value.isNotBlank())
            require(encryptedSecretUpdatedAtEpochMillis != null)
        }
    }
}

/**
 * Field-level sync metadata for Live channel personalization.
 *
 * A nullable value with a non-null clock is an explicit tombstone. A null clock means the field
 * has never participated in sync on this installation. Keys use syncSourceId + providerKey rather
 * than local channelId so provider refreshes and another device can resolve the same channel.
 */
@Entity(
    tableName = "device_sync_channels",
    primaryKeys = ["syncSourceId", "providerKey"],
    indices = [Index(value = ["syncSourceId"])],
)
data class DeviceSyncChannelEntity(
    val syncSourceId: String,
    val providerKey: String,
    val localDisplayName: String? = null,
    val localDisplayNameUpdatedAtEpochMillis: Long? = null,
    val localDisplayNameRevision: Long? = null,
    val localDisplayNameDeviceId: String? = null,
    val manualOrder: Long? = null,
    val manualOrderUpdatedAtEpochMillis: Long? = null,
    val manualOrderRevision: Long? = null,
    val manualOrderDeviceId: String? = null,
    val hidden: Boolean? = null,
    val hiddenUpdatedAtEpochMillis: Long? = null,
    val hiddenRevision: Long? = null,
    val hiddenDeviceId: String? = null,
    val favoriteOrder: Long? = null,
    val favoriteAddedAtEpochMillis: Long? = null,
    val favoriteUpdatedAtEpochMillis: Long? = null,
    val favoriteRevision: Long? = null,
    val favoriteDeviceId: String? = null,
) {
    init {
        require(syncSourceId.isNotBlank())
        require(providerKey.isNotBlank())
        localDisplayName?.let { require(it.isNotBlank()) }
        manualOrder?.let { require(it >= 0L) }
        favoriteOrder?.let { require(it >= 0L) }
        favoriteAddedAtEpochMillis?.let { require(it >= 0L) }
        require((favoriteOrder == null) == (favoriteAddedAtEpochMillis == null))
        requireOptionalClock(
            localDisplayNameUpdatedAtEpochMillis,
            localDisplayNameRevision,
            localDisplayNameDeviceId,
        )
        requireOptionalClock(
            manualOrderUpdatedAtEpochMillis,
            manualOrderRevision,
            manualOrderDeviceId,
        )
        requireOptionalClock(hiddenUpdatedAtEpochMillis, hiddenRevision, hiddenDeviceId)
        requireOptionalClock(favoriteUpdatedAtEpochMillis, favoriteRevision, favoriteDeviceId)
        if (localDisplayName != null) require(localDisplayNameUpdatedAtEpochMillis != null)
        if (manualOrder != null) require(manualOrderUpdatedAtEpochMillis != null)
        if (hidden != null) require(hiddenUpdatedAtEpochMillis != null)
        if (favoriteOrder != null) require(favoriteUpdatedAtEpochMillis != null)
    }
}

/** Custom groups use their syncGroupId as the local group id when materialized on another device. */
@Entity(tableName = "device_sync_groups")
data class DeviceSyncGroupEntity(
    @androidx.room.PrimaryKey val syncGroupId: String,
    val name: String,
    val nameUpdatedAtEpochMillis: Long,
    val nameRevision: Long,
    val nameDeviceId: String,
    val groupOrder: Long,
    val groupOrderUpdatedAtEpochMillis: Long,
    val groupOrderRevision: Long,
    val groupOrderDeviceId: String,
    val deleted: Boolean,
    val deletedUpdatedAtEpochMillis: Long,
    val deletedRevision: Long,
    val deletedDeviceId: String,
) {
    init {
        require(syncGroupId.isNotBlank())
        require(name.isNotBlank())
        require(groupOrder >= 0L)
        requireClock(nameUpdatedAtEpochMillis, nameRevision, nameDeviceId)
        requireClock(groupOrderUpdatedAtEpochMillis, groupOrderRevision, groupOrderDeviceId)
        requireClock(deletedUpdatedAtEpochMillis, deletedRevision, deletedDeviceId)
    }
}

/**
 * Membership rows also survive local membership/group removal. order=null with a valid clock is
 * the tombstone for a removed membership.
 */
@Entity(
    tableName = "device_sync_group_memberships",
    primaryKeys = ["syncGroupId", "syncSourceId", "providerKey"],
    indices = [
        Index(value = ["syncGroupId"]),
        Index(value = ["syncSourceId", "providerKey"]),
    ],
)
data class DeviceSyncGroupMembershipEntity(
    val syncGroupId: String,
    val syncSourceId: String,
    val providerKey: String,
    val groupOrder: Long?,
    val updatedAtEpochMillis: Long,
    val revision: Long,
    val deviceId: String,
) {
    init {
        require(syncGroupId.isNotBlank())
        require(syncSourceId.isNotBlank())
        require(providerKey.isNotBlank())
        groupOrder?.let { require(it >= 0L) }
        requireClock(updatedAtEpochMillis, revision, deviceId)
    }
}

@Dao
interface DeviceSyncDao {
    @Upsert
    suspend fun upsertLocalState(state: DeviceSyncLocalStateEntity)

    @Query("SELECT * FROM device_sync_local_state WHERE stateKey = :stateKey LIMIT 1")
    suspend fun localState(
        stateKey: String = DEVICE_SYNC_LOCAL_STATE_KEY,
    ): DeviceSyncLocalStateEntity?

    @Upsert
    suspend fun upsertSource(source: DeviceSyncSourceEntity)

    @Upsert
    suspend fun upsertSources(sources: List<DeviceSyncSourceEntity>)

    @Query("SELECT * FROM device_sync_sources ORDER BY syncSourceId ASC")
    suspend fun allSources(): List<DeviceSyncSourceEntity>

    @Query("SELECT * FROM device_sync_sources WHERE syncSourceId = :syncSourceId LIMIT 1")
    suspend fun sourceBySyncId(syncSourceId: String): DeviceSyncSourceEntity?

    @Query("SELECT * FROM device_sync_sources WHERE localSourceId = :localSourceId LIMIT 1")
    suspend fun sourceByLocalId(localSourceId: String): DeviceSyncSourceEntity?

    @Upsert
    suspend fun upsertChannel(channel: DeviceSyncChannelEntity)

    @Upsert
    suspend fun upsertChannels(channels: List<DeviceSyncChannelEntity>)

    @Query(
        "SELECT * FROM device_sync_channels " +
            "WHERE syncSourceId = :syncSourceId ORDER BY providerKey ASC",
    )
    suspend fun channelsForSource(syncSourceId: String): List<DeviceSyncChannelEntity>

    @Query(
        "SELECT * FROM device_sync_channels " +
            "WHERE syncSourceId = :syncSourceId AND providerKey = :providerKey LIMIT 1",
    )
    suspend fun channel(
        syncSourceId: String,
        providerKey: String,
    ): DeviceSyncChannelEntity?

    @Upsert
    suspend fun upsertGroup(group: DeviceSyncGroupEntity)

    @Upsert
    suspend fun upsertGroups(groups: List<DeviceSyncGroupEntity>)

    @Query("SELECT * FROM device_sync_groups ORDER BY syncGroupId ASC")
    suspend fun allGroups(): List<DeviceSyncGroupEntity>

    @Upsert
    suspend fun upsertMembership(membership: DeviceSyncGroupMembershipEntity)

    @Upsert
    suspend fun upsertMemberships(memberships: List<DeviceSyncGroupMembershipEntity>)

    @Query(
        "SELECT * FROM device_sync_group_memberships " +
            "WHERE syncGroupId = :syncGroupId ORDER BY syncSourceId ASC, providerKey ASC",
    )
    suspend fun membershipsForGroup(syncGroupId: String): List<DeviceSyncGroupMembershipEntity>

    @Query(
        "SELECT * FROM device_sync_group_memberships " +
            "ORDER BY syncGroupId ASC, syncSourceId ASC, providerKey ASC",
    )
    suspend fun allMemberships(): List<DeviceSyncGroupMembershipEntity>
}

private fun requireClock(
    updatedAtEpochMillis: Long,
    revision: Long,
    deviceId: String,
) {
    require(updatedAtEpochMillis >= 0L)
    require(revision >= 0L)
    require(deviceId.isNotBlank())
}

private fun requireOptionalClock(
    updatedAtEpochMillis: Long?,
    revision: Long?,
    deviceId: String?,
) {
    val present = listOf(
        updatedAtEpochMillis != null,
        revision != null,
        deviceId != null,
    )
    require(present.all { it } || present.none { it }) { "Sync clock must be complete or absent" }
    updatedAtEpochMillis?.let { require(it >= 0L) }
    revision?.let { require(it >= 0L) }
    deviceId?.let { require(it.isNotBlank()) }
}
