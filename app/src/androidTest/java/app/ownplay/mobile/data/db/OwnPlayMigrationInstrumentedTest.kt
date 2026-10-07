package app.ownplay.mobile.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwnPlayMigrationInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OwnPlayDatabase::class.java,
    )

    @Test
    fun migration1To4PreservesLegacyChannelPersonalizationAndBuildsProviderBridge() {
        helper.createDatabase(DB_V1, 1).apply {
            insertSourceAndChannel()
            execSQL(
                "INSERT INTO channel_personalization VALUES " +
                    "('legacy-channel',1,1,'Local Legacy',NULL,7)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB_V1,
            4,
            true,
            OwnPlayDatabase.MIGRATION_1_2,
            OwnPlayDatabase.MIGRATION_2_3,
            OwnPlayDatabase.MIGRATION_3_4,
        )

        assertEquals("Local Legacy", db.text(
            "SELECT localName FROM channel_personalization WHERE channelId='legacy-channel'",
        ))
        assertEquals("PROVIDER", db.text(
            "SELECT activeMode FROM live_organization_preferences WHERE sourceId='source-legacy'",
        ))
        assertEquals("legacy-news", db.text(
            "SELECT categoryId FROM live_channel_membership_personalization " +
                "WHERE sourceId='source-legacy' AND channelId='legacy-channel'",
        ))
        assertEquals(1, db.number(
            "SELECT hidden FROM live_channel_membership_personalization " +
                "WHERE sourceId='source-legacy' AND channelId='legacy-channel'",
        ))
        assertEquals(7, db.number(
            "SELECT manualOrder FROM live_channel_membership_personalization " +
                "WHERE sourceId='source-legacy' AND channelId='legacy-channel'",
        ))
        db.close()
    }

    @Test
    fun migration2To4BridgesLegacyCategoryAndChannelPersonalization() {
        helper.createDatabase(DB_V2, 2).apply {
            insertSourceAndChannel()
            execSQL(
                "INSERT INTO category_personalization VALUES " +
                    "('source-legacy','LIVE','legacy-news',1,4)",
            )
            execSQL(
                "INSERT INTO channel_personalization VALUES " +
                    "('legacy-channel',0,1,NULL,NULL,6)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB_V2,
            4,
            true,
            OwnPlayDatabase.MIGRATION_2_3,
            OwnPlayDatabase.MIGRATION_3_4,
        )

        assertEquals("PROVIDER", db.text(
            "SELECT activeMode FROM live_organization_preferences WHERE sourceId='source-legacy'",
        ))
        assertEquals(1, db.number(
            "SELECT hidden FROM live_category_scope_personalization " +
                "WHERE sourceId='source-legacy' AND organizationMode='PROVIDER' " +
                "AND categoryId='legacy-news'",
        ))
        assertEquals(4, db.number(
            "SELECT manualOrder FROM live_category_scope_personalization " +
                "WHERE sourceId='source-legacy' AND organizationMode='PROVIDER' " +
                "AND categoryId='legacy-news'",
        ))
        assertEquals(1, db.number(
            "SELECT hidden FROM live_channel_membership_personalization " +
                "WHERE sourceId='source-legacy' AND organizationMode='PROVIDER' " +
                "AND categoryId='legacy-news' AND channelId='legacy-channel'",
        ))
        assertEquals(6, db.number(
            "SELECT manualOrder FROM live_channel_membership_personalization " +
                "WHERE sourceId='source-legacy' AND organizationMode='PROVIDER' " +
                "AND categoryId='legacy-news' AND channelId='legacy-channel'",
        ))
        db.close()
    }

    @Test
    fun migration3To4AddsLibraryTitlePersonalizationWithoutChangingExistingLibraryState() {
        helper.createDatabase(DB_V3, 3).apply {
            execSQL(
                "INSERT INTO sources VALUES " +
                    "('source-library','Library','XTREAM','https://library.invalid','source-library',1,1,1)",
            )
            execSQL(
                "INSERT INTO movies VALUES " +
                    "('movie-1','source-library','11','movies','Movie One',NULL,NULL,NULL,NULL,3,1,1)",
            )
            execSQL(
                "INSERT INTO media_favorites VALUES ('source-library','MOVIE','movie-1',123)",
            )
            execSQL(
                "INSERT INTO playback_progress VALUES ('source-library','MOVIE','movie-1',60000,120000,0,456)",
            )
            execSQL(
                "INSERT INTO category_personalization VALUES ('source-library','MOVIE','movies',1,2)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB_V3,
            4,
            true,
            OwnPlayDatabase.MIGRATION_3_4,
        )

        assertEquals(1, db.number("SELECT COUNT(*) FROM movies WHERE movieId='movie-1'"))
        assertEquals(1, db.number("SELECT COUNT(*) FROM media_favorites WHERE contentId='movie-1'"))
        assertEquals(60000, db.number("SELECT positionMs FROM playback_progress WHERE contentId='movie-1'"))
        assertEquals(1, db.number(
            "SELECT hidden FROM category_personalization " +
                "WHERE sourceId='source-library' AND kind='MOVIE' AND categoryKey='movies'",
        ))
        db.execSQL(
            "INSERT INTO library_title_personalization VALUES " +
                "('source-library','MOVIE','movie-1',1,4)",
        )
        assertEquals(1, db.number(
            "SELECT hidden FROM library_title_personalization " +
                "WHERE sourceId='source-library' AND mediaKind='MOVIE' AND contentId='movie-1'",
        ))
        assertEquals(4, db.number(
            "SELECT manualOrder FROM library_title_personalization " +
                "WHERE sourceId='source-library' AND mediaKind='MOVIE' AND contentId='movie-1'",
        ))
        db.close()
    }

    @Test
    fun migration4To5AddsDurableSyncPersistenceWithoutChangingExistingSources() {
        helper.createDatabase(DB_V4, 4).apply {
            execSQL(
                "INSERT INTO sources VALUES " +
                    "('source-sync','Sync','XTREAM','https://sync.invalid','source-sync',1,1,1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB_V4,
            5,
            true,
            OwnPlayDatabase.MIGRATION_4_5,
        )

        assertEquals(1, db.number("SELECT COUNT(*) FROM sources WHERE sourceId='source-sync'"))
        db.execSQL(
            "INSERT INTO sync_outbox VALUES " +
                "('mutation-1','account-1','device-1','SOURCES','source-sync',NULL,'{}',10,0,NULL)",
        )
        db.execSQL(
            "INSERT INTO sync_cursor VALUES ('account-1','device-1',8,20)",
        )
        db.execSQL(
            "INSERT INTO sync_applied_state VALUES " +
                "('account-1','device-1','SOURCES','source-sync',8,20)",
        )
        db.execSQL(
            "INSERT INTO source_account_link VALUES ('source-sync','account-1',30)",
        )

        assertEquals(1, db.number("SELECT COUNT(*) FROM sync_outbox WHERE mutationId='mutation-1'"))
        assertEquals(8, db.number(
            "SELECT lastSuccessfulSyncRevision FROM sync_cursor " +
                "WHERE accountId='account-1' AND deviceId='device-1'",
        ))
        assertEquals(8, db.number(
            "SELECT recordRevision FROM sync_applied_state " +
                "WHERE accountId='account-1' AND deviceId='device-1' " +
                "AND namespace='SOURCES' AND entityKey='source-sync'",
        ))
        assertEquals("account-1", db.text(
            "SELECT accountId FROM source_account_link WHERE sourceId='source-sync'",
        ))

        db.execSQL("DELETE FROM sources WHERE sourceId='source-sync'")
        assertEquals(0, db.number(
            "SELECT COUNT(*) FROM source_account_link WHERE sourceId='source-sync'",
        ))
        db.close()
    }

    @Test
    fun migration5To6AddsOutboxOperationWithoutChangingExistingMutation() {
        helper.createDatabase(DB_V5, 5).apply {
            execSQL(
                "INSERT INTO sync_outbox " +
                    "(mutationId,accountId,deviceId,namespace,entityKey,observedServerRevision," +
                    "payloadJson,createdAt,attemptCount,lastAttemptAt) VALUES " +
                    "('mutation-v5','account-1','device-1','SOURCES','source-1',7,'{}',10,2,11)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB_V5,
            6,
            true,
            OwnPlayDatabase.MIGRATION_5_6,
        )

        assertEquals("UPSERT", db.text(
            "SELECT operation FROM sync_outbox WHERE mutationId='mutation-v5'",
        ))
        assertEquals(7, db.number(
            "SELECT observedServerRevision FROM sync_outbox WHERE mutationId='mutation-v5'",
        ))
        assertEquals(2, db.number(
            "SELECT attemptCount FROM sync_outbox WHERE mutationId='mutation-v5'",
        ))
        db.close()
    }


    @Test
    fun migration6To7AddsViewingStateSyncMetadataWithoutChangingExistingSyncState() {
        helper.createDatabase(DB_V6, 6).apply {
            execSQL(
                "INSERT INTO sources VALUES " +
                    "('33333333-3333-4333-8333-333333333333','Viewing','XTREAM','https://sync.invalid','source-sync',1,1,1)",
            )
            execSQL(
                "INSERT INTO sync_cursor VALUES " +
                    "('account-1','22222222-2222-4222-8222-222222222222',9,20)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB_V6,
            7,
            true,
            OwnPlayDatabase.MIGRATION_6_7,
        )

        assertEquals(9, db.number(
            "SELECT lastSuccessfulSyncRevision FROM sync_cursor " +
                "WHERE accountId='account-1' AND deviceId='22222222-2222-4222-8222-222222222222'",
        ))
        db.execSQL(
            "INSERT INTO viewing_state_sync VALUES " +
                "('33333333-3333-4333-8333-333333333333','MOVIE','movie/42',NULL,'account-1'," +
                "120000,3600000,0,0,9,100,0,0,200)",
        )
        assertEquals(9, db.number(
            "SELECT lastServerRevision FROM viewing_state_sync " +
                "WHERE providerMediaId='movie/42'",
        ))
        db.execSQL("DELETE FROM sources WHERE sourceId='33333333-3333-4333-8333-333333333333'")
        assertEquals(0, db.number("SELECT COUNT(*) FROM viewing_state_sync"))
        db.close()
    }

    @Test
    fun migration7To8DropsAccountSyncTablesWithoutChangingLocalState() {
        helper.createDatabase(DB_V7, 7).apply {
            execSQL(
                "INSERT INTO sources VALUES " +
                    "('source-local','Local','XTREAM','https://local.invalid','source-local',1,1,1)",
            )
            execSQL(
                "INSERT INTO playback_progress VALUES " +
                    "('source-local','MOVIE','movie-local',60000,120000,0,456)",
            )
            execSQL(
                "INSERT INTO sync_outbox " +
                    "(mutationId,accountId,deviceId,namespace,entityKey,observedServerRevision,payloadJson,operation,createdAt,attemptCount,lastAttemptAt) VALUES " +
                    "('mutation-v7','account-1','device-1','SOURCES','source-local',7,'{}','UPSERT',10,0,NULL)",
            )
            execSQL("INSERT INTO sync_cursor VALUES ('account-1','device-1',7,20)")
            execSQL(
                "INSERT INTO sync_applied_state VALUES " +
                    "('account-1','device-1','SOURCES','source-local',7,20)",
            )
            execSQL("INSERT INTO source_account_link VALUES ('source-local','account-1',30)")
            execSQL(
                "INSERT INTO viewing_state_sync VALUES " +
                    "('source-local','MOVIE','movie-local',NULL,'account-1',60000,120000,0,0,7,100,0,0,200)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB_V7,
            8,
            true,
            OwnPlayDatabase.MIGRATION_7_8,
        )

        assertEquals(1, db.number("SELECT COUNT(*) FROM sources WHERE sourceId='source-local'"))
        assertEquals(60000, db.number(
            "SELECT positionMs FROM playback_progress WHERE sourceId='source-local' " +
                "AND mediaType='MOVIE' AND contentId='movie-local'",
        ))
        assertEquals(0, db.number("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='sync_outbox'"))
        assertEquals(0, db.number("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='sync_cursor'"))
        assertEquals(0, db.number("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='sync_applied_state'"))
        assertEquals(0, db.number("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='source_account_link'"))
        assertEquals(0, db.number("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='viewing_state_sync'"))
        db.close()
    }

    @Test
    fun migration8To9AddsPersistentGuideAndCascadesWithSource() {
        helper.createDatabase(DB_V8, 8).apply {
            execSQL(
                "INSERT INTO sources VALUES " +
                    "('source-guide','Guide','XTREAM','https://guide.invalid','source-guide',1,1,1)",
            )
            execSQL(
                "INSERT INTO live_channels VALUES " +
                    "('guide-channel','source-guide','guide-provider','22',NULL,'Guide Channel'," +
                    "'guide-22',NULL,NULL,'opaque-guide',0,1,1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB_V8,
            9,
            true,
            OwnPlayDatabase.MIGRATION_8_9,
        )
        db.execSQL(
            "INSERT INTO live_guide_snapshots VALUES " +
                "('source-guide','guide-channel','FULL',123456,0)",
        )
        db.execSQL(
            "INSERT INTO live_guide_programs VALUES " +
                "('source-guide','guide-channel','FULL','program-1','News',100,200,0)",
        )
        assertEquals(1, db.number("SELECT COUNT(*) FROM live_guide_programs"))
        db.execSQL("DELETE FROM sources WHERE sourceId='source-guide'")
        assertEquals(0, db.number("SELECT COUNT(*) FROM live_guide_snapshots"))
        assertEquals(0, db.number("SELECT COUNT(*) FROM live_guide_programs"))
        db.close()
    }

    private fun SupportSQLiteDatabase.insertSourceAndChannel() {
        execSQL(
            "INSERT INTO sources VALUES " +
                "('source-legacy','Legacy','XTREAM','https://legacy.invalid','source-legacy',1,1,1)",
        )
        execSQL(
            "INSERT INTO live_channels VALUES " +
                "('legacy-channel','source-legacy','legacy-provider','1','legacy-news'," +
                "'Legacy News',NULL,NULL,NULL,'opaque-legacy',0,1,1)",
        )
    }

    private fun SupportSQLiteDatabase.text(sql: String): String? = query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

    private fun SupportSQLiteDatabase.number(sql: String): Int = query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private companion object {
        const val DB_V1 = "migration-v1-to-v4.db"
        const val DB_V2 = "migration-v2-to-v4.db"
        const val DB_V3 = "migration-v3-to-v4.db"
        const val DB_V4 = "migration-v4-to-v5.db"
        const val DB_V5 = "migration-v5-to-v6.db"
        const val DB_V6 = "migration-v6-to-v7.db"
        const val DB_V7 = "migration-v7-to-v8.db"
        const val DB_V8 = "migration-v8-to-v9.db"
    }
}
