package app.ownplay.player.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ownplay.player.live.ingest.IncomingLiveCatalog
import app.ownplay.player.live.ingest.IncomingLiveCategory
import app.ownplay.player.live.ingest.IncomingLiveChannel
import app.ownplay.player.live.ingest.InitialLiveCatalogIngestResult
import app.ownplay.player.live.ingest.InitialLiveCatalogIngestor
import app.ownplay.player.live.ingest.PlaybackLocatorDescriptor
import app.ownplay.player.live.ingest.RoomLiveCatalogPersistence
import app.ownplay.player.persistence.OwnPlayDatabase
import app.ownplay.player.persistence.PlaylistSourceEntity
import app.ownplay.player.persistence.SourceKinds
import app.ownplay.player.persistence.secure.AndroidKeystoreSensitiveValueStore
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeFixtureSeederTest {
    @Test
    fun seedAuthorizedPlaybackFixtures() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Runtime fixture seeding is opt-in",
            arguments.getString(ENABLED_ARGUMENT)?.equals("true", ignoreCase = true) == true,
        )

        val fixtures = FIXTURE_ALIASES.mapNotNull { alias ->
            arguments.getString("$FIXTURE_ARGUMENT_PREFIX$alias")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { value -> alias to value }
        }
        check(fixtures.any { (alias, _) -> alias == REQUIRED_BASELINE_ALIAS }) {
            "$REQUIRED_BASELINE_ALIAS fixture argument is required"
        }

        val context = instrumentation.targetContext
        check(context.packageName == TARGET_PACKAGE) {
            "Runtime fixture seeder target package mismatch"
        }
        check(android.os.Process.myUid() == context.applicationInfo.uid) {
            "Runtime fixture seeder must execute under target application UID"
        }

        val database = OwnPlayDatabase.create(context)
        val sensitiveValueStore = AndroidKeystoreSensitiveValueStore(context)

        try {
            val sourceDao = database.playlistSourceDao()
            val now = System.currentTimeMillis()
            val existingSource = sourceDao.getById(SOURCE_ID)
            val sourceLocatorRef = existingSource?.locatorRef
                ?: sensitiveValueStore.put(SOURCE_LOCATOR_SENTINEL).value

            sourceDao.upsert(
                PlaylistSourceEntity(
                    sourceId = SOURCE_ID,
                    name = SOURCE_NAME,
                    sourceKind = SourceKinds.REMOTE_M3U,
                    locatorRef = sourceLocatorRef,
                    credentialRef = null,
                    enabled = true,
                    createdAtEpochMillis = existingSource?.createdAtEpochMillis ?: now,
                    updatedAtEpochMillis = now,
                ),
            )

            val catalog = IncomingLiveCatalog(
                categories = listOf(
                    IncomingLiveCategory(
                        providerKey = CATEGORY_KEY,
                        name = CATEGORY_NAME,
                        parentProviderKey = null,
                        providerOrder = 0L,
                    ),
                ),
                channels = fixtures.mapIndexed { index, (alias, locator) ->
                    IncomingLiveChannel(
                        providerKey = "runtime:$alias",
                        providerStreamId = null,
                        providerCategoryKey = CATEGORY_KEY,
                        providerName = alias,
                        tvgId = null,
                        tvgName = null,
                        locatorValue = PlaybackLocatorDescriptor.directUrl(locator),
                        logoValue = null,
                        providerOrder = index.toLong(),
                    )
                },
            )

            val result = InitialLiveCatalogIngestor(
                persistence = RoomLiveCatalogPersistence(database),
                sensitiveValueStore = sensitiveValueStore,
            ).ingest(
                sourceId = SOURCE_ID,
                generation = now,
                catalog = catalog,
            )

            check(result is InitialLiveCatalogIngestResult.Success) {
                "Runtime fixture ingest did not succeed"
            }
            check(result.channelCount == fixtures.size) {
                "Runtime fixture ingest count mismatch"
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        const val ENABLED_ARGUMENT = "ownplay.runtime.seed.enabled"
        const val FIXTURE_ARGUMENT_PREFIX = "ownplay.runtime.fixture."
        const val REQUIRED_BASELINE_ALIAS = "GOOD_HLS"
        const val TARGET_PACKAGE = "app.ownplay.player"
        const val SOURCE_ID = "ownplay-runtime-fixtures-v1"
        const val SOURCE_NAME = "Runtime Fixtures"
        const val SOURCE_LOCATOR_SENTINEL = "ownplay-runtime-fixture-source-v1"
        const val CATEGORY_KEY = "runtime-fixtures"
        const val CATEGORY_NAME = "Runtime Fixtures"

        val FIXTURE_ALIASES = listOf(
            "GOOD_HLS",
            "TRACKED_HLS",
            "NAV_A",
            "NAV_B",
            "NAV_C",
            "UNAVAILABLE",
            "TIMEOUT",
            "UNSUPPORTED",
            "AUTH_401_OR_403",
        )
    }
}
