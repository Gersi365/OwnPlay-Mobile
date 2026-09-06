package app.ownplay.player.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwnPlayDatabaseMigration45Test {
    @Test
    fun migration4To5PreservesDownloadsAndCreatesSeriesHierarchy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val databaseName = "ownplay-migration-v4-v5-${UUID.randomUUID()}.db"
        val helper = MigrationTestHelper(
            instrumentation,
            OwnPlayDatabase::class.java,
        )

        try {
            helper.createDatabase(databaseName, 4).use { database ->
                database.execSQL(
                    """
                    INSERT INTO playlist_sources (
                        sourceId, name, sourceKind, locatorRef, credentialRef, enabled,
                        createdAtEpochMillis, updatedAtEpochMillis
                    ) VALUES (
                        'source-series', 'Series Source', 'xtream', 'locator-ref', 'credential-ref',
                        1, 1, 1
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO media_downloads (
                        downloadId, sourceId, mediaKind, contentId, providerStreamId,
                        title, state, bytesDownloaded, createdAtEpochMillis, updatedAtEpochMillis
                    ) VALUES (
                        'download-before-v5', 'source-series', 'MOVIE', 'movie-1', 42,
                        'Existing Movie', 'QUEUED', 0, 10, 10
                    )
                    """.trimIndent(),
                )
            }

            helper.runMigrationsAndValidate(
                databaseName,
                5,
                true,
                MIGRATION_4_5,
            ).use { database ->
                database.query(
                    "SELECT title FROM media_downloads WHERE downloadId = 'download-before-v5'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Existing Movie", cursor.getString(0))
                }

                database.execSQL(
                    """
                    INSERT INTO provider_series_categories (
                        categoryId, sourceId, providerCategoryKey, name,
                        providerOrder, lastSeenGeneration
                    ) VALUES ('cat-1', 'source-series', '10', 'Drama', 0, 1)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO provider_series (
                        seriesId, sourceId, providerSeriesId, providerCategoryKey,
                        providerName, providerOrder, lastSeenGeneration
                    ) VALUES ('series-1', 'source-series', '100', '10', 'Series One', 0, 1)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO provider_series_seasons (
                        seasonId, seriesId, seasonNumber, name
                    ) VALUES ('season-1', 'series-1', 1, 'Season 1')
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO provider_series_episodes (
                        episodeId, seriesId, seasonId, providerEpisodeId,
                        seasonNumber, episodeNumber, title
                    ) VALUES ('episode-1', 'series-1', 'season-1', '1001', 1, 1, 'Pilot')
                    """.trimIndent(),
                )
                database.query(
                    "SELECT title FROM provider_series_episodes WHERE episodeId = 'episode-1'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Pilot", cursor.getString(0))
                }
            }
        } finally {
            instrumentation.targetContext.deleteDatabase(databaseName)
        }
    }
}
