package app.ownplay.player.persistence.sync

import androidx.room.migration.Migration

/**
 * Adds cross-device sync metadata without modifying or deleting existing provider/personalization
 * tables. Sync tables intentionally have no cascading foreign keys: tombstones must survive local
 * deletion so stale devices cannot resurrect removed state.
 */
val MIGRATION_5_6 = Migration(5, 6) { database ->
    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `device_sync_local_state` (
            `stateKey` TEXT NOT NULL,
            `deviceId` TEXT NOT NULL,
            `nextRevision` INTEGER NOT NULL,
            `updatedAtEpochMillis` INTEGER NOT NULL,
            PRIMARY KEY(`stateKey`)
        )
        """.trimIndent(),
    )
    database.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_device_sync_local_state_deviceId` " +
            "ON `device_sync_local_state` (`deviceId`)",
    )
    database.execSQL(
        """
        INSERT OR IGNORE INTO `device_sync_local_state` (
            `stateKey`, `deviceId`, `nextRevision`, `updatedAtEpochMillis`
        ) VALUES (
            'local',
            lower(hex(randomblob(16))),
            1,
            CAST(strftime('%s','now') AS INTEGER) * 1000
        )
        """.trimIndent(),
    )

    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `device_sync_sources` (
            `syncSourceId` TEXT NOT NULL,
            `localSourceId` TEXT,
            `sourceKind` TEXT NOT NULL,
            `locatorFingerprint` TEXT,
            `displayName` TEXT NOT NULL,
            `displayNameUpdatedAtEpochMillis` INTEGER NOT NULL,
            `displayNameRevision` INTEGER NOT NULL,
            `displayNameDeviceId` TEXT NOT NULL,
            `enabled` INTEGER NOT NULL,
            `enabledUpdatedAtEpochMillis` INTEGER NOT NULL,
            `enabledRevision` INTEGER NOT NULL,
            `enabledDeviceId` TEXT NOT NULL,
            `deleted` INTEGER NOT NULL,
            `deletedUpdatedAtEpochMillis` INTEGER NOT NULL,
            `deletedRevision` INTEGER NOT NULL,
            `deletedDeviceId` TEXT NOT NULL,
            `encryptedSecretRef` TEXT,
            `encryptedSecretUpdatedAtEpochMillis` INTEGER,
            `encryptedSecretRevision` INTEGER,
            `encryptedSecretDeviceId` TEXT,
            PRIMARY KEY(`syncSourceId`)
        )
        """.trimIndent(),
    )
    database.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_device_sync_sources_localSourceId` " +
            "ON `device_sync_sources` (`localSourceId`)",
    )
    database.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_device_sync_sources_sourceKind` " +
            "ON `device_sync_sources` (`sourceKind`)",
    )

    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `device_sync_channels` (
            `syncSourceId` TEXT NOT NULL,
            `providerKey` TEXT NOT NULL,
            `localDisplayName` TEXT,
            `localDisplayNameUpdatedAtEpochMillis` INTEGER,
            `localDisplayNameRevision` INTEGER,
            `localDisplayNameDeviceId` TEXT,
            `manualOrder` INTEGER,
            `manualOrderUpdatedAtEpochMillis` INTEGER,
            `manualOrderRevision` INTEGER,
            `manualOrderDeviceId` TEXT,
            `hidden` INTEGER,
            `hiddenUpdatedAtEpochMillis` INTEGER,
            `hiddenRevision` INTEGER,
            `hiddenDeviceId` TEXT,
            `favoriteOrder` INTEGER,
            `favoriteAddedAtEpochMillis` INTEGER,
            `favoriteUpdatedAtEpochMillis` INTEGER,
            `favoriteRevision` INTEGER,
            `favoriteDeviceId` TEXT,
            PRIMARY KEY(`syncSourceId`, `providerKey`)
        )
        """.trimIndent(),
    )
    database.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_device_sync_channels_syncSourceId` " +
            "ON `device_sync_channels` (`syncSourceId`)",
    )

    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `device_sync_groups` (
            `syncGroupId` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `nameUpdatedAtEpochMillis` INTEGER NOT NULL,
            `nameRevision` INTEGER NOT NULL,
            `nameDeviceId` TEXT NOT NULL,
            `groupOrder` INTEGER NOT NULL,
            `groupOrderUpdatedAtEpochMillis` INTEGER NOT NULL,
            `groupOrderRevision` INTEGER NOT NULL,
            `groupOrderDeviceId` TEXT NOT NULL,
            `deleted` INTEGER NOT NULL,
            `deletedUpdatedAtEpochMillis` INTEGER NOT NULL,
            `deletedRevision` INTEGER NOT NULL,
            `deletedDeviceId` TEXT NOT NULL,
            PRIMARY KEY(`syncGroupId`)
        )
        """.trimIndent(),
    )

    database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `device_sync_group_memberships` (
            `syncGroupId` TEXT NOT NULL,
            `syncSourceId` TEXT NOT NULL,
            `providerKey` TEXT NOT NULL,
            `groupOrder` INTEGER,
            `updatedAtEpochMillis` INTEGER NOT NULL,
            `revision` INTEGER NOT NULL,
            `deviceId` TEXT NOT NULL,
            PRIMARY KEY(`syncGroupId`, `syncSourceId`, `providerKey`)
        )
        """.trimIndent(),
    )
    database.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_device_sync_group_memberships_syncGroupId` " +
            "ON `device_sync_group_memberships` (`syncGroupId`)",
    )
    database.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_device_sync_group_memberships_syncSourceId_providerKey` " +
            "ON `device_sync_group_memberships` (`syncSourceId`, `providerKey`)",
    )

    // Existing source IDs become initial sync IDs. A future reconciliation pass may replace/link
    // independently-created legacy sources by a non-secret locator fingerprint.
    database.execSQL(
        """
        INSERT OR IGNORE INTO `device_sync_sources` (
            `syncSourceId`, `localSourceId`, `sourceKind`, `locatorFingerprint`,
            `displayName`, `displayNameUpdatedAtEpochMillis`, `displayNameRevision`, `displayNameDeviceId`,
            `enabled`, `enabledUpdatedAtEpochMillis`, `enabledRevision`, `enabledDeviceId`,
            `deleted`, `deletedUpdatedAtEpochMillis`, `deletedRevision`, `deletedDeviceId`,
            `encryptedSecretRef`, `encryptedSecretUpdatedAtEpochMillis`,
            `encryptedSecretRevision`, `encryptedSecretDeviceId`
        )
        SELECT
            source.`sourceId`,
            source.`sourceId`,
            source.`sourceKind`,
            NULL,
            source.`name`,
            source.`updatedAtEpochMillis`,
            0,
            local.`deviceId`,
            source.`enabled`,
            source.`updatedAtEpochMillis`,
            0,
            local.`deviceId`,
            0,
            source.`updatedAtEpochMillis`,
            0,
            local.`deviceId`,
            NULL,
            NULL,
            NULL,
            NULL
        FROM `playlist_sources` AS source
        CROSS JOIN `device_sync_local_state` AS local
        WHERE local.`stateKey` = 'local'
        """.trimIndent(),
    )

    // Personalization that already has a meaningful timestamp keeps it. Legacy customization
    // fields without historical timestamps start at clock zero so they cannot masquerade as a
    // recent edit merely because the app was upgraded today.
    database.execSQL(
        """
        INSERT OR IGNORE INTO `device_sync_channels` (
            `syncSourceId`, `providerKey`,
            `localDisplayName`, `localDisplayNameUpdatedAtEpochMillis`,
            `localDisplayNameRevision`, `localDisplayNameDeviceId`,
            `manualOrder`, `manualOrderUpdatedAtEpochMillis`, `manualOrderRevision`, `manualOrderDeviceId`,
            `hidden`, `hiddenUpdatedAtEpochMillis`, `hiddenRevision`, `hiddenDeviceId`,
            `favoriteOrder`, `favoriteAddedAtEpochMillis`, `favoriteUpdatedAtEpochMillis`,
            `favoriteRevision`, `favoriteDeviceId`
        )
        SELECT
            channel.`sourceId`,
            channel.`providerKey`,
            customization.`localDisplayName`,
            CASE WHEN customization.`localDisplayName` IS NULL THEN NULL ELSE 0 END,
            CASE WHEN customization.`localDisplayName` IS NULL THEN NULL ELSE 0 END,
            CASE WHEN customization.`localDisplayName` IS NULL THEN NULL ELSE local.`deviceId` END,
            customization.`manualOrder`,
            CASE WHEN customization.`manualOrder` IS NULL THEN NULL ELSE 0 END,
            CASE WHEN customization.`manualOrder` IS NULL THEN NULL ELSE 0 END,
            CASE WHEN customization.`manualOrder` IS NULL THEN NULL ELSE local.`deviceId` END,
            CASE WHEN hidden.`channelId` IS NULL THEN NULL ELSE 1 END,
            hidden.`hiddenAtEpochMillis`,
            CASE WHEN hidden.`channelId` IS NULL THEN NULL ELSE 0 END,
            CASE WHEN hidden.`channelId` IS NULL THEN NULL ELSE local.`deviceId` END,
            favorite.`favoriteOrder`,
            favorite.`addedAtEpochMillis`,
            favorite.`addedAtEpochMillis`,
            CASE WHEN favorite.`channelId` IS NULL THEN NULL ELSE 0 END,
            CASE WHEN favorite.`channelId` IS NULL THEN NULL ELSE local.`deviceId` END
        FROM `provider_channels` AS channel
        CROSS JOIN `device_sync_local_state` AS local
        LEFT JOIN `channel_customizations` AS customization
            ON customization.`channelId` = channel.`channelId`
        LEFT JOIN `hidden_entries` AS hidden
            ON hidden.`channelId` = channel.`channelId`
        LEFT JOIN `favorite_entries` AS favorite
            ON favorite.`channelId` = channel.`channelId`
        WHERE local.`stateKey` = 'local'
          AND (
              customization.`localDisplayName` IS NOT NULL OR
              customization.`manualOrder` IS NOT NULL OR
              hidden.`channelId` IS NOT NULL OR
              favorite.`channelId` IS NOT NULL
          )
        """.trimIndent(),
    )

    database.execSQL(
        """
        INSERT OR IGNORE INTO `device_sync_groups` (
            `syncGroupId`, `name`, `nameUpdatedAtEpochMillis`, `nameRevision`, `nameDeviceId`,
            `groupOrder`, `groupOrderUpdatedAtEpochMillis`, `groupOrderRevision`, `groupOrderDeviceId`,
            `deleted`, `deletedUpdatedAtEpochMillis`, `deletedRevision`, `deletedDeviceId`
        )
        SELECT
            groups.`groupId`,
            groups.`name`,
            groups.`createdAtEpochMillis`,
            0,
            local.`deviceId`,
            groups.`groupOrder`,
            groups.`createdAtEpochMillis`,
            0,
            local.`deviceId`,
            0,
            groups.`createdAtEpochMillis`,
            0,
            local.`deviceId`
        FROM `custom_groups` AS groups
        CROSS JOIN `device_sync_local_state` AS local
        WHERE local.`stateKey` = 'local'
        """.trimIndent(),
    )

    database.execSQL(
        """
        INSERT OR IGNORE INTO `device_sync_group_memberships` (
            `syncGroupId`, `syncSourceId`, `providerKey`, `groupOrder`,
            `updatedAtEpochMillis`, `revision`, `deviceId`
        )
        SELECT
            membership.`groupId`,
            channel.`sourceId`,
            channel.`providerKey`,
            membership.`groupOrder`,
            0,
            0,
            local.`deviceId`
        FROM `custom_group_memberships` AS membership
        INNER JOIN `provider_channels` AS channel
            ON channel.`channelId` = membership.`channelId`
        CROSS JOIN `device_sync_local_state` AS local
        WHERE local.`stateKey` = 'local'
        """.trimIndent(),
    )
}
