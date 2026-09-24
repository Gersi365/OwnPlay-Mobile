package app.ownplay.mobile.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceCascadeInstrumentedTest {
    private lateinit var database: OwnPlayDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OwnPlayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seed(database.openHelper.writableDatabase)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun deletingOneSourceCascadesOnlyItsScopedDurableState() = runBlocking {
        assertEquals(1, database.sourceDao().delete("source-a"))
        val db = database.openHelper.writableDatabase

        sourceScopedTables.forEach { table ->
            assertEquals("$table source-a", 0, db.count(table, "sourceId = 'source-a'"))
        }
        assertEquals(0, db.count("channel_personalization", "channelId = 'channel-a'"))
        assertEquals(0, db.count("custom_group_memberships", "groupId = 'group-a'"))
        assertEquals(0, db.count("episodes", "seriesId = 'series-a'"))

        assertEquals(1, db.count("sources", "sourceId = 'source-b'"))
        sourceBExpectedCounts.forEach { (table, expectedCount) ->
            assertEquals(
                "$table source-b",
                expectedCount,
                db.count(table, "sourceId = 'source-b'"),
            )
        }
        assertEquals(1, db.count("channel_personalization", "channelId = 'channel-b'"))
        assertEquals(1, db.count("custom_group_memberships", "groupId = 'group-b'"))
        assertEquals(1, db.count("episodes", "seriesId = 'series-b'"))
    }

    @Test
    fun durableSourceAndDownloadStateSurviveDatabaseCloseAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "phase12-reopen.db"
        context.deleteDatabase(name)
        try {
            val first = persistentDatabase(context, name)
            first.sourceDao().insert(
                SourceEntity("source-reopen", "Reopen", "XTREAM", "https://reopen.invalid", "source-reopen", true, 1, 1),
            )
            first.downloadDao().upsert(
                DownloadEntity(
                    "download-reopen", "source-reopen", "MOVIE", "movie-reopen", "Movie",
                    "opaque", "PAUSED", 42, 100, null, null, null, 2, 3,
                ),
            )
            first.close()

            val reopened = persistentDatabase(context, name)
            assertEquals("Reopen", reopened.sourceDao().get("source-reopen")?.displayName)
            assertEquals("PAUSED", reopened.downloadDao().get("download-reopen")?.state)
            assertEquals(42L, reopened.downloadDao().get("download-reopen")?.bytesDownloaded)
            reopened.close()
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun persistentDatabase(context: Context, name: String): OwnPlayDatabase =
        Room.databaseBuilder(context, OwnPlayDatabase::class.java, name)
            .addMigrations(OwnPlayDatabase.MIGRATION_1_2, OwnPlayDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO sources VALUES ('source-a','A','XTREAM','https://a.invalid','source-a',1,1,1)")
        db.execSQL("INSERT INTO sources VALUES ('source-b','B','XTREAM','https://b.invalid','source-b',1,2,2)")
        db.execSQL("INSERT INTO provider_categories VALUES ('source-a','LIVE','cat-a','pa','A News',0,1,1)")
        db.execSQL("INSERT INTO provider_categories VALUES ('source-b','LIVE','cat-b','pb','B News',0,1,1)")
        db.execSQL("INSERT INTO category_personalization VALUES ('source-a','LIVE','cat-a',1,3)")
        db.execSQL("INSERT INTO category_personalization VALUES ('source-b','LIVE','cat-b',0,4)")
        db.execSQL("INSERT INTO live_channels VALUES ('channel-a','source-a','pca','1','cat-a','A One',NULL,NULL,NULL,'opaque-a',0,1,1)")
        db.execSQL("INSERT INTO live_channels VALUES ('channel-b','source-b','pcb','2','cat-b','B One',NULL,NULL,NULL,'opaque-b',0,1,1)")
        db.execSQL("INSERT INTO channel_personalization VALUES ('channel-a',1,0,'Local A',NULL,2)")
        db.execSQL("INSERT INTO channel_personalization VALUES ('channel-b',0,1,'Local B',NULL,3)")
        db.execSQL("INSERT INTO custom_groups VALUES ('group-a','source-a','Group A',0)")
        db.execSQL("INSERT INTO custom_groups VALUES ('group-b','source-b','Group B',1)")
        db.execSQL("INSERT INTO custom_group_memberships VALUES ('group-a','channel-a',0)")
        db.execSQL("INSERT INTO custom_group_memberships VALUES ('group-b','channel-b',0)")
        db.execSQL("INSERT INTO movies VALUES ('movie-a','source-a','10','vod-a','Movie A',NULL,NULL,'mp4',NULL,0,1,1)")
        db.execSQL("INSERT INTO movies VALUES ('movie-b','source-b','20','vod-b','Movie B',NULL,NULL,'mp4',NULL,0,1,1)")
        db.execSQL("INSERT INTO series VALUES ('series-a','source-a','30','series-cat-a','Series A',NULL,NULL,NULL,NULL,0,1,1)")
        db.execSQL("INSERT INTO series VALUES ('series-b','source-b','40','series-cat-b','Series B',NULL,NULL,NULL,NULL,0,1,1)")
        db.execSQL("INSERT INTO episodes VALUES ('episode-a','series-a',1,1,'31','Episode A',NULL,'opaque-ea','mp4',1,1)")
        db.execSQL("INSERT INTO episodes VALUES ('episode-b','series-b',1,1,'41','Episode B',NULL,'opaque-eb','mp4',1,1)")
        db.execSQL("INSERT INTO media_favorites VALUES ('source-a','MOVIE','movie-a',10)")
        db.execSQL("INSERT INTO media_favorites VALUES ('source-b','MOVIE','movie-b',11)")
        db.execSQL("INSERT INTO playback_progress VALUES ('source-a','MOVIE','movie-a',100,1000,0,11)")
        db.execSQL("INSERT INTO playback_progress VALUES ('source-b','MOVIE','movie-b',200,1000,0,12)")
        db.execSQL("INSERT INTO downloads VALUES ('download-a','source-a','MOVIE','movie-a','Movie A','opaque-da','COMPLETED',100,100,'content://fixture/a','sha256:fixture',NULL,12,13)")
        db.execSQL("INSERT INTO downloads VALUES ('download-b','source-b','MOVIE','movie-b','Movie B','opaque-db','PAUSED',50,100,NULL,NULL,NULL,14,15)")
        db.execSQL("INSERT INTO refresh_state VALUES ('source-a',1,'SUCCESS',20,20,NULL)")
        db.execSQL("INSERT INTO refresh_state VALUES ('source-b',2,'SUCCESS',30,30,NULL)")
        db.execSQL("INSERT INTO live_organization_preferences VALUES ('source-a','OWNPLAY')")
        db.execSQL("INSERT INTO live_organization_preferences VALUES ('source-b','PROVIDER')")
        db.execSQL("INSERT INTO ownplay_live_categories VALUES ('source-a','country:AL',NULL,'Albania',NULL,'AUTO',1,1)")
        db.execSQL("INSERT INTO ownplay_live_categories VALUES ('source-a','country:AL:NEWS','country:AL','News','NEWS','AUTO',1,1)")
        db.execSQL("INSERT INTO ownplay_live_categories VALUES ('source-b','country:IT',NULL,'Italy',NULL,'AUTO',1,1)")
        db.execSQL("INSERT INTO ownplay_live_categories VALUES ('source-b','country:IT:SPORTS','country:IT','Sports','SPORTS','AUTO',1,1)")
        db.execSQL("INSERT INTO live_category_scope_personalization VALUES ('source-a','OWNPLAY','country:AL:NEWS',0,1)")
        db.execSQL("INSERT INTO live_category_scope_personalization VALUES ('source-b','OWNPLAY','country:IT:SPORTS',0,2)")
        db.execSQL("INSERT INTO ownplay_live_channel_memberships VALUES ('source-a','country:AL:NEWS','channel-a',1,'AUTO','HIGH',NULL,1,1)")
        db.execSQL("INSERT INTO ownplay_live_channel_memberships VALUES ('source-b','country:IT:SPORTS','channel-b',1,'AUTO','HIGH',NULL,1,1)")
        db.execSQL("INSERT INTO live_channel_membership_personalization VALUES ('source-a','OWNPLAY','country:AL:NEWS','channel-a',0,1)")
        db.execSQL("INSERT INTO live_channel_membership_personalization VALUES ('source-b','OWNPLAY','country:IT:SPORTS','channel-b',0,2)")
    }

    private fun SupportSQLiteDatabase.count(table: String, where: String): Int =
        query("SELECT COUNT(*) FROM $table WHERE $where").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        val sourceScopedTables = listOf(
            "provider_categories",
            "category_personalization",
            "live_channels",
            "custom_groups",
            "movies",
            "series",
            "media_favorites",
            "playback_progress",
            "downloads",
            "refresh_state",
            "live_organization_preferences",
            "ownplay_live_categories",
            "live_category_scope_personalization",
            "ownplay_live_channel_memberships",
            "live_channel_membership_personalization",
        )

        val sourceBExpectedCounts = mapOf(
            "provider_categories" to 1,
            "category_personalization" to 1,
            "live_channels" to 1,
            "custom_groups" to 1,
            "movies" to 1,
            "series" to 1,
            "media_favorites" to 1,
            "playback_progress" to 1,
            "downloads" to 1,
            "refresh_state" to 1,
            "live_organization_preferences" to 1,
            "ownplay_live_categories" to 2,
            "live_category_scope_personalization" to 1,
            "ownplay_live_channel_memberships" to 1,
            "live_channel_membership_personalization" to 1,
        )
    }
}
