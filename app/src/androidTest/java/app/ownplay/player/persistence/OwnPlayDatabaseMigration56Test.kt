package app.ownplay.player.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ownplay.player.persistence.sync.MIGRATION_5_6
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwnPlayDatabaseMigration56Test {
    @Test
    fun migration5To6PreservesPersonalizationAndBootstrapsSyncMetadata() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val databaseName = "ownplay-migration-v5-v6-${UUID.randomUUID()}.db"
        val helper = MigrationTestHelper(
            instrumentation,
            OwnPlayDatabase::class.java,
        )

        try {
            helper.createDatabase(databaseName, 5).use { database ->
                database.execSQL(
                    """
                    INSERT INTO playlist_sources (
                        sourceId, name, sourceKind, locatorRef, credentialRef, enabled,
                        createdAtEpochMillis, updatedAtEpochMillis
                    ) VALUES (
                        'source-1', 'Living Room', 'xtream', 'locator-ref', 'credential-ref',
                        1, 100, 200
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO provider_channels (
                        channelId, sourceId, providerKey, providerStreamId, providerName,
                        streamLocatorRef, providerOrder, availability, lastSeenGeneration
                    ) VALUES (
                        'channel-1', 'source-1', 'xtream:live:42', '42', 'News One',
                        'stream-ref', 4, 'available', 200
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO channel_customizations (
                        channelId, localDisplayName, logoOverrideRef, manualOrder
                    ) VALUES (
                        'channel-1', 'My News', NULL, 2
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "INSERT INTO hidden_entries (channelId, hiddenAtEpochMillis) " +
                        "VALUES ('channel-1', 300)",
                )
                database.execSQL(
                    """
                    INSERT INTO favorite_entries (channelId, favoriteOrder, addedAtEpochMillis)
                    VALUES ('channel-1', 1, 400)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO custom_groups (groupId, name, groupOrder, createdAtEpochMillis)
                    VALUES ('group-1', 'News', 0, 500)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO custom_group_memberships (groupId, channelId, groupOrder)
                    VALUES ('group-1', 'channel-1', 0)
                    """.trimIndent(),
                )
            }

            helper.runMigrationsAndValidate(
                databaseName,
                6,
                true,
                MIGRATION_5_6,
            ).use { database ->
                // Existing state is untouched.
                database.query(
                    "SELECT name, enabled FROM playlist_sources WHERE sourceId = 'source-1'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Living Room", cursor.getString(0))
                    assertEquals(1, cursor.getInt(1))
                }
                database.query(
                    "SELECT localDisplayName, manualOrder FROM channel_customizations " +
                        "WHERE channelId = 'channel-1'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("My News", cursor.getString(0))
                    assertEquals(2L, cursor.getLong(1))
                }
                database.query(
                    "SELECT favoriteOrder FROM favorite_entries WHERE channelId = 'channel-1'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                }

                val deviceId = database.query(
                    "SELECT deviceId FROM device_sync_local_state WHERE stateKey = 'local'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(0)
                }
                assertNotNull(deviceId)
                assertFalse(deviceId.isBlank())

                database.query(
                    """
                    SELECT syncSourceId, localSourceId, sourceKind, displayName, enabled, deleted,
                           encryptedSecretRef
                    FROM device_sync_sources
                    WHERE syncSourceId = 'source-1'
                    """.trimIndent(),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("source-1", cursor.getString(0))
                    assertEquals("source-1", cursor.getString(1))
                    assertEquals("xtream", cursor.getString(2))
                    assertEquals("Living Room", cursor.getString(3))
                    assertEquals(1, cursor.getInt(4))
                    assertEquals(0, cursor.getInt(5))
                    assertTrue(cursor.isNull(6))
                }

                database.query(
                    """
                    SELECT localDisplayName, localDisplayNameUpdatedAtEpochMillis,
                           manualOrder, manualOrderUpdatedAtEpochMillis,
                           hidden, hiddenUpdatedAtEpochMillis,
                           favoriteOrder, favoriteAddedAtEpochMillis, favoriteUpdatedAtEpochMillis
                    FROM device_sync_channels
                    WHERE syncSourceId = 'source-1' AND providerKey = 'xtream:live:42'
                    """.trimIndent(),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("My News", cursor.getString(0))
                    assertEquals(0L, cursor.getLong(1))
                    assertEquals(2L, cursor.getLong(2))
                    assertEquals(0L, cursor.getLong(3))
                    assertEquals(1, cursor.getInt(4))
                    assertEquals(300L, cursor.getLong(5))
                    assertEquals(1L, cursor.getLong(6))
                    assertEquals(400L, cursor.getLong(7))
                    assertEquals(400L, cursor.getLong(8))
                }

                database.query(
                    "SELECT name, groupOrder, deleted FROM device_sync_groups " +
                        "WHERE syncGroupId = 'group-1'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("News", cursor.getString(0))
                    assertEquals(0L, cursor.getLong(1))
                    assertEquals(0, cursor.getInt(2))
                }
                database.query(
                    """
                    SELECT groupOrder FROM device_sync_group_memberships
                    WHERE syncGroupId = 'group-1'
                      AND syncSourceId = 'source-1'
                      AND providerKey = 'xtream:live:42'
                    """.trimIndent(),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0L, cursor.getLong(0))
                }

                // Sync metadata deliberately has no cascade back to local personalization rows.
                database.execSQL("DELETE FROM playlist_sources WHERE sourceId = 'source-1'")
                database.query(
                    "SELECT COUNT(*) FROM provider_channels WHERE sourceId = 'source-1'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0L, cursor.getLong(0))
                }
                database.query(
                    "SELECT COUNT(*) FROM device_sync_sources WHERE syncSourceId = 'source-1'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                }
                database.query(
                    """
                    SELECT COUNT(*) FROM device_sync_channels
                    WHERE syncSourceId = 'source-1' AND providerKey = 'xtream:live:42'
                    """.trimIndent(),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                }
                database.query(
                    "SELECT COUNT(*) FROM device_sync_group_memberships " +
                        "WHERE syncGroupId = 'group-1'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                }
            }
        } finally {
            instrumentation.targetContext.deleteDatabase(databaseName)
        }
    }
}
