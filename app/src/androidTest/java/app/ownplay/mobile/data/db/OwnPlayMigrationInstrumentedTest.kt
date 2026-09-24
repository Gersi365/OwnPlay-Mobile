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
    fun migration1To3PreservesLegacyChannelPersonalizationAndBuildsProviderBridge() {
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
            3,
            true,
            OwnPlayDatabase.MIGRATION_1_2,
            OwnPlayDatabase.MIGRATION_2_3,
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
    fun migration2To3BridgesLegacyCategoryAndChannelPersonalization() {
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
            3,
            true,
            OwnPlayDatabase.MIGRATION_2_3,
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
        const val DB_V1 = "migration-v1-to-v3.db"
        const val DB_V2 = "migration-v2-to-v3.db"
    }
}
