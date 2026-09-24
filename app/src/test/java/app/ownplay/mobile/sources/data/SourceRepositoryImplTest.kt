package app.ownplay.mobile.sources.data

import app.ownplay.mobile.data.db.LiveChannelEntity
import app.ownplay.mobile.data.db.MovieEntity
import app.ownplay.mobile.data.db.ProviderCategoryEntity
import app.ownplay.mobile.data.db.RefreshStateDao
import app.ownplay.mobile.data.db.RefreshStateEntity
import app.ownplay.mobile.data.db.SeriesEntity
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.data.db.SourceEntity
import app.ownplay.mobile.data.prefs.ActiveSourceSelectionStore
import app.ownplay.mobile.data.security.CredentialStore
import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceInput
import app.ownplay.mobile.sources.domain.SourceMutationRejection
import app.ownplay.mobile.sources.domain.SourceMutationResult
import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import app.ownplay.mobile.sources.domain.SourceReconnectInput
import app.ownplay.mobile.sources.domain.SourceRefreshResult
import app.ownplay.mobile.sources.domain.SourceType
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRepositoryImplTest {
    @Test
    fun addXtreamSourceStoresCredentialsOutsideRoomAndSelectsFirstSource() = runBlocking {
        val sourceDao = FakeSourceDao()
        val refreshStateDao = FakeRefreshStateDao()
        val activeSourceStore = FakeActiveSourceStore()
        val credentialStore = FakeCredentialStore()
        val repository = repository(
            sourceDao = sourceDao,
            refreshStateDao = refreshStateDao,
            activeSourceStore = activeSourceStore,
            credentialStore = credentialStore,
        )

        val result = repository.addSource(
            SourceInput.Xtream(
                displayName = "  Home  ",
                serverUrl = "HTTPS://Example.COM/portal/",
                username = "alice",
                password = "secret-password",
            ),
        )

        assertEquals(SourceMutationResult.Success(SourceId("source-1")), result)
        val row = sourceDao.get("source-1")!!
        assertEquals("Home", row.displayName)
        assertEquals("XTREAM", row.type)
        assertEquals("https://example.com/portal", row.baseLocator)
        assertEquals("source-1", row.credentialReference)
        assertFalse(row.toString().contains("alice"))
        assertFalse(row.toString().contains("secret-password"))
        assertTrue(credentialStore.secrets[SourceId("source-1")] is SourceSecret.Xtream)
        assertEquals("source-1", activeSourceStore.currentSelectedSourceId())
    }

    @Test
    fun addM3uSourceRedactsSecretBearingLocatorAndReportsRefreshState() = runBlocking {
        val sourceDao = FakeSourceDao()
        val refreshStateDao = FakeRefreshStateDao()
        val activeSourceStore = FakeActiveSourceStore()
        val credentialStore = FakeCredentialStore()
        val repository = repository(
            sourceDao = sourceDao,
            refreshStateDao = refreshStateDao,
            activeSourceStore = activeSourceStore,
            credentialStore = credentialStore,
        )

        repository.addSource(
            SourceInput.M3u(
                displayName = "Remote list",
                playlistUrl = "https://media.example/list.m3u?token=playlist-secret",
                epgUrl = "https://media.example/epg.xml?key=epg-secret",
            ),
        )
        refreshStateDao.upsert(
            RefreshStateEntity(
                sourceId = "source-1",
                generation = 4,
                state = "SUCCESS",
                lastAttempt = 100L,
                lastSuccess = 90L,
                errorCode = null,
            ),
        )

        val row = sourceDao.get("source-1")!!
        assertEquals("https://media.example/list.m3u", row.baseLocator)
        assertFalse(row.baseLocator.contains("playlist-secret"))
        val storedSecret = credentialStore.secrets[SourceId("source-1")] as SourceSecret.M3uRemote
        assertTrue(storedSecret.playlistUrl.contains("playlist-secret"))
        assertTrue(storedSecret.epgUrl!!.contains("epg-secret"))

        val summary = repository.observeSources().first().single()
        assertEquals("https://media.example/list.m3u", summary.connectionLabel)
        assertEquals(90L, summary.lastSuccessfulRefreshAtEpochMs)
    }

    @Test
    fun duplicateConnectionIsRejectedBeforeSecondCredentialWrite() = runBlocking {
        val sourceDao = FakeSourceDao()
        val credentialStore = FakeCredentialStore()
        val repository = repository(
            sourceDao = sourceDao,
            credentialStore = credentialStore,
        )

        repository.addSource(
            SourceInput.Xtream("One", "https://example.com/api", "u1", "p1"),
        )
        val duplicate = repository.addSource(
            SourceInput.Xtream("Two", "https://EXAMPLE.com/api/", "u2", "p2"),
        )

        assertEquals(
            SourceMutationResult.Rejected(SourceMutationRejection.DUPLICATE_SOURCE),
            duplicate,
        )
        assertEquals(1, credentialStore.putCalls)
        assertEquals(1, sourceDao.getAll().size)
    }

    @Test
    fun failedRoomInsertRollsBackCredentialWrite() = runBlocking {
        val sourceDao = FakeSourceDao().apply { failInsert = true }
        val credentialStore = FakeCredentialStore()
        val repository = repository(
            sourceDao = sourceDao,
            credentialStore = credentialStore,
        )

        val result = repository.addSource(
            SourceInput.Xtream("Home", "https://example.com", "user", "password"),
        )

        assertEquals(
            SourceMutationResult.Rejected(SourceMutationRejection.STORAGE_FAILURE),
            result,
        )
        assertTrue(credentialStore.secrets.isEmpty())
        assertTrue(sourceDao.getAll().isEmpty())
    }

    @Test
    fun renameSourceNormalizesDisplayNameWithoutChangingConnectionOrCredentials() = runBlocking {
        val original = source(id = "source-a", enabled = true, updatedAt = 10L)
        val sourceDao = FakeSourceDao(listOf(original))
        val credentialStore = FakeCredentialStore().apply {
            seed(SourceId("source-a"), SourceSecret.Xtream("user", "secret"))
        }
        val repository = repository(
            sourceDao = sourceDao,
            credentialStore = credentialStore,
            nowMillis = { 50L },
        )

        val result = repository.renameSource(SourceId("source-a"), "  Living Room  ")

        assertEquals(SourceMutationResult.Success(SourceId("source-a")), result)
        val renamed = sourceDao.get("source-a")!!
        assertEquals("Living Room", renamed.displayName)
        assertEquals(original.baseLocator, renamed.baseLocator)
        assertEquals(original.credentialReference, renamed.credentialReference)
        assertEquals(50L, renamed.updatedAt)
        assertTrue(credentialStore.secrets.containsKey(SourceId("source-a")))
    }

    @Test
    fun renameSourceRejectsInvalidNameWithoutMutatingSource() = runBlocking {
        val original = source(id = "source-a", enabled = true, updatedAt = 10L)
        val sourceDao = FakeSourceDao(listOf(original))
        val repository = repository(sourceDao = sourceDao)

        val result = repository.renameSource(SourceId("source-a"), "   ")

        assertEquals(
            SourceMutationResult.Rejected(SourceMutationRejection.INVALID_NAME),
            result,
        )
        assertEquals(original, sourceDao.get("source-a"))
    }

    @Test
    fun reconnectRestoredXtreamPreservesStableIdentityAndEnablesSource() = runBlocking {
        val restored = source(id = "source-a", enabled = false, updatedAt = 10L).copy(
            displayName = "Living Room",
            baseLocator = "https://example.com/portal",
            credentialReference = null,
        )
        val sourceDao = FakeSourceDao(listOf(restored))
        val credentialStore = FakeCredentialStore()
        val repository = repository(
            sourceDao = sourceDao,
            credentialStore = credentialStore,
            nowMillis = { 50L },
        )

        val result = repository.reconnectSource(
            SourceId("source-a"),
            SourceReconnectInput.Xtream(
                serverUrl = "HTTPS://EXAMPLE.COM/portal/",
                username = "alice",
                password = "secret-password",
            ),
        )

        assertEquals(SourceMutationResult.Success(SourceId("source-a")), result)
        assertEquals(1, sourceDao.getAll().size)
        val connected = sourceDao.get("source-a")!!
        assertEquals("Living Room", connected.displayName)
        assertEquals(restored.baseLocator, connected.baseLocator)
        assertEquals("source-a", connected.credentialReference)
        assertTrue(connected.enabled)
        assertEquals(50L, connected.updatedAt)
        val secret = credentialStore.secrets[SourceId("source-a")] as SourceSecret.Xtream
        assertEquals("alice", secret.username)
        assertEquals("secret-password", secret.password)
    }

    @Test
    fun reconnectRestoredM3uAcceptsSecretBearingUrlWithoutPersistingSecretLocator() = runBlocking {
        val restored = source(id = "source-a", enabled = false, updatedAt = 10L).copy(
            type = "M3U",
            baseLocator = "https://media.example/list.m3u",
            credentialReference = null,
        )
        val sourceDao = FakeSourceDao(listOf(restored))
        val credentialStore = FakeCredentialStore()
        val repository = repository(sourceDao = sourceDao, credentialStore = credentialStore)

        val result = repository.reconnectSource(
            SourceId("source-a"),
            SourceReconnectInput.M3u(
                playlistUrl = "https://media.example/list.m3u?token=playlist-secret",
                epgUrl = "https://media.example/epg.xml?token=epg-secret",
            ),
        )

        assertEquals(SourceMutationResult.Success(SourceId("source-a")), result)
        val connected = sourceDao.get("source-a")!!
        assertEquals("https://media.example/list.m3u", connected.baseLocator)
        assertFalse(connected.baseLocator.contains("playlist-secret"))
        val secret = credentialStore.secrets[SourceId("source-a")] as SourceSecret.M3uRemote
        assertTrue(secret.playlistUrl.contains("playlist-secret"))
        assertTrue(secret.epgUrl!!.contains("epg-secret"))
    }

    @Test
    fun reconnectRejectsConnectionMismatchWithoutCredentialWrite() = runBlocking {
        val restored = source(id = "source-a", enabled = false, updatedAt = 10L).copy(
            baseLocator = "https://expected.example/portal",
            credentialReference = null,
        )
        val credentialStore = FakeCredentialStore()
        val repository = repository(
            sourceDao = FakeSourceDao(listOf(restored)),
            credentialStore = credentialStore,
        )

        val result = repository.reconnectSource(
            SourceId("source-a"),
            SourceReconnectInput.Xtream("https://other.example/portal", "alice", "secret"),
        )

        assertEquals(
            SourceMutationResult.Rejected(SourceMutationRejection.INVALID_CONNECTION),
            result,
        )
        assertEquals(0, credentialStore.putCalls)
    }

    @Test
    fun reconnectRoomFailureRollsBackNewCredentialAndLeavesSourceDisabled() = runBlocking {
        val restored = source(id = "source-a", enabled = false, updatedAt = 10L).copy(
            baseLocator = "https://example.com/portal",
            credentialReference = null,
        )
        val sourceDao = FakeSourceDao(listOf(restored)).apply { failUpdate = true }
        val credentialStore = FakeCredentialStore()
        val repository = repository(sourceDao = sourceDao, credentialStore = credentialStore)

        val result = repository.reconnectSource(
            SourceId("source-a"),
            SourceReconnectInput.Xtream("https://example.com/portal", "alice", "secret"),
        )

        assertEquals(
            SourceMutationResult.Rejected(SourceMutationRejection.STORAGE_FAILURE),
            result,
        )
        assertFalse(sourceDao.get("source-a")!!.enabled)
        assertNull(sourceDao.get("source-a")!!.credentialReference)
        assertTrue(credentialStore.secrets.isEmpty())
    }

    @Test
    fun noPersistedSelectionProducesNoActiveSourceEvenWhenEnabledSourcesExist() = runBlocking {
        val repository = repository(
            sourceDao = FakeSourceDao(
                listOf(
                    source(id = "source-a", enabled = true, updatedAt = 20L),
                    source(id = "source-b", enabled = true, updatedAt = 10L),
                ),
            ),
            activeSourceStore = FakeActiveSourceStore(null),
        )

        assertNull(repository.observeActiveSource().first())
    }

    @Test
    fun clearActiveSourcePersistsExplicitNoActiveState() = runBlocking {
        val activeSourceStore = FakeActiveSourceStore("source-a")
        val repository = repository(
            sourceDao = FakeSourceDao(listOf(source(id = "source-a", enabled = true, updatedAt = 1L))),
            activeSourceStore = activeSourceStore,
        )

        assertTrue(repository.clearActiveSource())
        assertNull(activeSourceStore.currentSelectedSourceId())
        assertNull(repository.observeActiveSource().first())
    }

    @Test
    fun disabledSourceCannotBecomeActive() = runBlocking {
        val disabled = source(
            id = "disabled",
            enabled = false,
            updatedAt = 2L,
        )
        val activeSourceStore = FakeActiveSourceStore()
        val repository = repository(
            sourceDao = FakeSourceDao(listOf(disabled)),
            activeSourceStore = activeSourceStore,
        )

        assertFalse(repository.setActiveSource(SourceId("disabled")))
        assertNull(activeSourceStore.currentSelectedSourceId())
    }

    @Test
    fun removingPersistedActiveSourceSelectsNextEnabledSourceAndDeletesSecret() = runBlocking {
        val active = source(id = "source-a", enabled = true, updatedAt = 20L)
        val fallback = source(id = "source-b", enabled = true, updatedAt = 10L)
        val sourceDao = FakeSourceDao(listOf(active, fallback))
        val activeSourceStore = FakeActiveSourceStore("source-a")
        val credentialStore = FakeCredentialStore().apply {
            seed(SourceId("source-a"), SourceSecret.Xtream("a", "a-secret"))
            seed(SourceId("source-b"), SourceSecret.Xtream("b", "b-secret"))
        }
        val repository = repository(
            sourceDao = sourceDao,
            activeSourceStore = activeSourceStore,
            credentialStore = credentialStore,
        )

        assertTrue(repository.removeSource(SourceId("source-a")))
        assertNull(sourceDao.get("source-a"))
        assertEquals("source-b", activeSourceStore.currentSelectedSourceId())
        assertFalse(credentialStore.secrets.containsKey(SourceId("source-a")))
        assertTrue(credentialStore.secrets.containsKey(SourceId("source-b")))
    }


    @Test
    fun removingNonActiveSourcePreservesActiveSelectionAndUnrelatedCredential() = runBlocking {
        val active = source(id = "source-a", enabled = true, updatedAt = 20L)
        val removed = source(id = "source-b", enabled = true, updatedAt = 10L)
        val sourceDao = FakeSourceDao(listOf(active, removed))
        val activeSourceStore = FakeActiveSourceStore("source-a")
        val credentialStore = FakeCredentialStore().apply {
            seed(SourceId("source-a"), SourceSecret.Xtream("a", "a-secret"))
            seed(SourceId("source-b"), SourceSecret.Xtream("b", "b-secret"))
        }
        val repository = repository(
            sourceDao = sourceDao,
            activeSourceStore = activeSourceStore,
            credentialStore = credentialStore,
        )

        assertTrue(repository.removeSource(SourceId("source-b")))

        assertEquals("source-a", activeSourceStore.currentSelectedSourceId())
        assertTrue(sourceDao.get("source-a") != null)
        assertNull(sourceDao.get("source-b"))
        assertTrue(credentialStore.secrets.containsKey(SourceId("source-a")))
        assertFalse(credentialStore.secrets.containsKey(SourceId("source-b")))
    }

    @Test
    fun removingOnlyActiveSourceClearsActiveSelection() = runBlocking {
        val sourceDao = FakeSourceDao(
            listOf(source(id = "source-a", enabled = true, updatedAt = 20L)),
        )
        val activeSourceStore = FakeActiveSourceStore("source-a")
        val credentialStore = FakeCredentialStore().apply {
            seed(SourceId("source-a"), SourceSecret.Xtream("a", "a-secret"))
        }
        val repository = repository(
            sourceDao = sourceDao,
            activeSourceStore = activeSourceStore,
            credentialStore = credentialStore,
        )

        assertTrue(repository.removeSource(SourceId("source-a")))

        assertNull(activeSourceStore.currentSelectedSourceId())
        assertNull(sourceDao.get("source-a"))
        assertTrue(credentialStore.secrets.isEmpty())
    }

    @Test
    fun removingActiveSourceSkipsDisabledFallbackAndUsesRepositoryOrder() = runBlocking {
        val active = source(id = "source-a", enabled = true, updatedAt = 30L)
        val disabled = source(id = "source-disabled", enabled = false, updatedAt = 25L)
        val expectedFallback = source(id = "source-b", enabled = true, updatedAt = 20L)
        val laterFallback = source(id = "source-c", enabled = true, updatedAt = 10L)
        val sourceDao = FakeSourceDao(
            listOf(active, disabled, laterFallback, expectedFallback),
        )
        val activeSourceStore = FakeActiveSourceStore("source-a")
        val credentialStore = FakeCredentialStore().apply {
            seed(SourceId("source-a"), SourceSecret.Xtream("a", "a-secret"))
        }
        val repository = repository(
            sourceDao = sourceDao,
            activeSourceStore = activeSourceStore,
            credentialStore = credentialStore,
        )

        assertTrue(repository.removeSource(SourceId("source-a")))

        assertEquals("source-b", activeSourceStore.currentSelectedSourceId())
    }

    @Test
    fun failedSourceRowDeleteRestoresPriorSelectionAndCredential() = runBlocking {
        val active = source(id = "source-a", enabled = true, updatedAt = 20L)
        val fallback = source(id = "source-b", enabled = true, updatedAt = 10L)
        val sourceDao = FakeSourceDao(listOf(active, fallback)).apply {
            failDelete = true
        }
        val activeSourceStore = FakeActiveSourceStore("source-a")
        val credentialStore = FakeCredentialStore().apply {
            seed(SourceId("source-a"), SourceSecret.Xtream("a", "a-secret"))
            seed(SourceId("source-b"), SourceSecret.Xtream("b", "b-secret"))
        }
        val repository = repository(
            sourceDao = sourceDao,
            activeSourceStore = activeSourceStore,
            credentialStore = credentialStore,
        )

        assertFalse(repository.removeSource(SourceId("source-a")))

        assertEquals("source-a", activeSourceStore.currentSelectedSourceId())
        assertTrue(sourceDao.get("source-a") != null)
        assertTrue(sourceDao.get("source-b") != null)
        assertTrue(credentialStore.secrets.containsKey(SourceId("source-a")))
        assertTrue(credentialStore.secrets.containsKey(SourceId("source-b")))
    }

    @Test
    fun addSourceRejectsProviderAuthenticationBeforePersistingAnything() = runBlocking {
        val sourceDao = FakeSourceDao()
        val refreshStateDao = FakeRefreshStateDao()
        val activeSourceStore = FakeActiveSourceStore()
        val credentialStore = FakeCredentialStore()
        val store = FakeCatalogRefreshStore(refreshStateDao)
        val repository = repository(
            sourceDao = sourceDao,
            refreshStateDao = refreshStateDao,
            activeSourceStore = activeSourceStore,
            credentialStore = credentialStore,
            catalogLoader = FakeSourceCatalogLoader(
                failure = SourceRefreshFailureCategory.AUTHENTICATION,
            ),
            catalogRefreshStore = store,
        )

        val result = repository.addSource(
            SourceInput.Xtream(
                displayName = "Home",
                serverUrl = "https://example.com",
                username = "alice",
                password = "wrong-password",
            ),
        )

        assertEquals(
            SourceMutationResult.Rejected(SourceMutationRejection.INVALID_CREDENTIALS),
            result,
        )
        assertTrue(sourceDao.getAll().isEmpty())
        assertTrue(credentialStore.secrets.isEmpty())
        assertNull(activeSourceStore.currentSelectedSourceId())
        assertEquals(0, store.commitCalls)
    }

    @Test
    fun successfulAddImportsCatalogExactlyOnceBeforeBecomingUsable() = runBlocking {
        val sourceDao = FakeSourceDao()
        val refreshStateDao = FakeRefreshStateDao()
        val activeSourceStore = FakeActiveSourceStore()
        val credentialStore = FakeCredentialStore()
        val store = FakeCatalogRefreshStore(refreshStateDao)
        val repository = repository(
            sourceDao = sourceDao,
            refreshStateDao = refreshStateDao,
            activeSourceStore = activeSourceStore,
            credentialStore = credentialStore,
            catalogRefreshStore = store,
            nowMillis = { 1_000L },
        )

        val result = repository.addSource(
            SourceInput.M3u(
                displayName = "Remote",
                playlistUrl = "https://media.example/list.m3u?token=secret",
                epgUrl = null,
            ),
        )

        assertEquals(SourceMutationResult.Success(SourceId("source-1")), result)
        assertEquals(1, store.commitCalls)
        assertEquals(1L, store.lastGeneration)
        assertEquals("source-1", activeSourceStore.currentSelectedSourceId())
        assertEquals("SUCCESS", refreshStateDao.get("source-1")?.state)
    }

    @Test
    fun reconnectValidationFailurePreservesLastWorkingSourceAndCredential() = runBlocking {
        val sourceId = SourceId("source-a")
        val original = source(id = sourceId.value, enabled = true, updatedAt = 10L).copy(
            baseLocator = "https://example.com/portal",
        )
        val sourceDao = FakeSourceDao(listOf(original))
        val refreshStateDao = FakeRefreshStateDao().apply {
            upsert(
                RefreshStateEntity(
                    sourceId = sourceId.value,
                    generation = 4L,
                    state = "FAILED",
                    lastAttempt = 50L,
                    lastSuccess = 40L,
                    errorCode = SourceRefreshFailureCategory.AUTHENTICATION.name,
                ),
            )
        }
        val credentialStore = FakeCredentialStore().apply {
            seed(sourceId, SourceSecret.Xtream("old-user", "old-password"))
        }
        val store = FakeCatalogRefreshStore(refreshStateDao)
        val repository = repository(
            sourceDao = sourceDao,
            refreshStateDao = refreshStateDao,
            credentialStore = credentialStore,
            catalogLoader = FakeSourceCatalogLoader(
                failure = SourceRefreshFailureCategory.AUTHENTICATION,
            ),
            catalogRefreshStore = store,
        )

        val result = repository.reconnectSource(
            sourceId,
            SourceReconnectInput.Xtream(
                serverUrl = "https://example.com/portal",
                username = "new-user",
                password = "wrong-password",
            ),
        )

        assertEquals(
            SourceMutationResult.Rejected(SourceMutationRejection.INVALID_CREDENTIALS),
            result,
        )
        assertEquals(original, sourceDao.get(sourceId.value))
        val retained = credentialStore.secrets[sourceId] as SourceSecret.Xtream
        assertEquals("old-user", retained.username)
        assertEquals("old-password", retained.password)
        assertEquals(0, store.commitCalls)
        assertEquals(
            SourceRefreshFailureCategory.AUTHENTICATION.name,
            refreshStateDao.get(sourceId.value)?.errorCode,
        )
    }

    @Test
    fun successfulRefreshAdvancesGenerationOnlyWhenSnapshotCommits() = runBlocking {
        val sourceId = SourceId("source-a")
        val refreshStateDao = FakeRefreshStateDao().apply {
            upsert(
                RefreshStateEntity(
                    sourceId = sourceId.value,
                    generation = 3,
                    state = "SUCCESS",
                    lastAttempt = 400L,
                    lastSuccess = 500L,
                    errorCode = null,
                ),
            )
        }
        val credentialStore = FakeCredentialStore().apply {
            seed(sourceId, SourceSecret.Xtream("user", "password"))
        }
        val loader = FakeSourceCatalogLoader()
        val store = FakeCatalogRefreshStore(refreshStateDao)
        val clock = ArrayDeque(listOf(1_000L, 1_100L))
        val repository = repository(
            sourceDao = FakeSourceDao(listOf(source(id = sourceId.value, enabled = true, updatedAt = 1L))),
            refreshStateDao = refreshStateDao,
            credentialStore = credentialStore,
            catalogLoader = loader,
            catalogRefreshStore = store,
            nowMillis = { clock.removeFirst() },
        )

        val result = repository.refreshSource(sourceId)

        assertEquals(SourceRefreshResult.Success, result)
        assertEquals(1, store.commitCalls)
        assertEquals(4L, store.lastGeneration)
        val state = refreshStateDao.get(sourceId.value)!!
        assertEquals(4L, state.generation)
        assertEquals("SUCCESS", state.state)
        assertEquals(1_100L, state.lastSuccess)
    }

    @Test
    fun failedCatalogLoadsPreserveSuccessfulGenerationForEveryFailureCategory() = runBlocking {
        val expectedMessages = linkedMapOf(
            SourceRefreshFailureCategory.AUTHENTICATION to "Source authentication failed.",
            SourceRefreshFailureCategory.NETWORK to "Source network request failed.",
            SourceRefreshFailureCategory.TIMEOUT to "Source request timed out.",
            SourceRefreshFailureCategory.INVALID_PAYLOAD to "Source returned invalid catalog data.",
            SourceRefreshFailureCategory.PROVIDER to "Source provider rejected the refresh.",
            SourceRefreshFailureCategory.TRANSIENT_PROVIDER to "Source provider is temporarily unavailable.",
            SourceRefreshFailureCategory.STORAGE to "Source refresh could not be saved.",
            SourceRefreshFailureCategory.UNKNOWN to "Source refresh failed.",
        )

        expectedMessages.forEach { (category, expectedMessage) ->
            val sourceId = SourceId("source-${category.name.lowercase()}")
            val refreshStateDao = FakeRefreshStateDao().apply {
                upsert(
                    RefreshStateEntity(
                        sourceId = sourceId.value,
                        generation = 7,
                        state = "SUCCESS",
                        lastAttempt = 700L,
                        lastSuccess = 650L,
                        errorCode = null,
                    ),
                )
            }
            val credentialStore = FakeCredentialStore().apply {
                seed(sourceId, SourceSecret.Xtream("user", "password"))
            }
            val store = FakeCatalogRefreshStore(refreshStateDao)
            val repository = repository(
                sourceDao = FakeSourceDao(
                    listOf(source(id = sourceId.value, enabled = true, updatedAt = 1L)),
                ),
                refreshStateDao = refreshStateDao,
                credentialStore = credentialStore,
                catalogLoader = FakeSourceCatalogLoader(failure = category),
                catalogRefreshStore = store,
            )

            val result = repository.refreshSource(sourceId)

            assertEquals(
                SourceRefreshResult.Failure(
                    category = category,
                    safeMessage = expectedMessage,
                ),
                result,
            )
            assertEquals(0, store.commitCalls)
            val state = refreshStateDao.get(sourceId.value)!!
            assertEquals(7L, state.generation)
            assertEquals(650L, state.lastSuccess)
            assertEquals("FAILED", state.state)
            assertEquals(category.name, state.errorCode)
        }
    }

    @Test
    fun failedCatalogCommitPreservesSuccessfulGenerationAndReportsStorageFailure() = runBlocking {
        val sourceId = SourceId("source-a")
        val refreshStateDao = FakeRefreshStateDao().apply {
            upsert(
                RefreshStateEntity(
                    sourceId = sourceId.value,
                    generation = 2,
                    state = "SUCCESS",
                    lastAttempt = 200L,
                    lastSuccess = 180L,
                    errorCode = null,
                ),
            )
        }
        val credentialStore = FakeCredentialStore().apply {
            seed(sourceId, SourceSecret.Xtream("user", "password"))
        }
        val store = FakeCatalogRefreshStore(refreshStateDao, failCommit = true)
        val repository = repository(
            sourceDao = FakeSourceDao(listOf(source(id = sourceId.value, enabled = true, updatedAt = 1L))),
            refreshStateDao = refreshStateDao,
            credentialStore = credentialStore,
            catalogRefreshStore = store,
        )

        val result = repository.refreshSource(sourceId)

        assertEquals(
            SourceRefreshResult.Failure(
                category = SourceRefreshFailureCategory.STORAGE,
                safeMessage = "Source refresh could not be saved.",
            ),
            result,
        )
        val state = refreshStateDao.get(sourceId.value)!!
        assertEquals(2L, state.generation)
        assertEquals(180L, state.lastSuccess)
        assertEquals("FAILED", state.state)
        assertEquals("STORAGE", state.errorCode)
    }

    @Test
    fun catalogReconciliationReusesLegacyProviderIdentityKeys() {
        val sourceId = SourceId("source-a")
        val snapshot = ProviderCatalogSnapshot(
            sourceType = SourceType.XTREAM,
            categories = listOf(
                ProviderCategoryRecord("LIVE", "7", "News", 0),
                ProviderCategoryRecord("MOVIE", "8", "Movies", 0),
                ProviderCategoryRecord("SERIES", "9", "Series", 0),
            ),
            liveChannels = listOf(
                ProviderLiveChannelRecord(
                    proposedChannelId = StableIdentity.xtreamLiveChannel(sourceId, "42"),
                    providerKey = "42",
                    providerStreamId = "42",
                    categoryProviderKey = "7",
                    name = "News One",
                    tvgId = "news.one",
                    tvgName = "News One",
                    logoUrl = null,
                    streamLocator = "xtream://live/42",
                    providerOrder = 0,
                ),
            ),
            movies = listOf(
                ProviderMovieRecord(
                    proposedMovieId = StableIdentity.xtreamMovie(sourceId, "52"),
                    providerStreamId = "52",
                    categoryProviderKey = "8",
                    name = "Movie",
                    posterUrl = null,
                    backdropUrl = null,
                    extension = "mp4",
                    rating = null,
                    providerOrder = 0,
                ),
            ),
            series = listOf(
                ProviderSeriesRecord(
                    proposedSeriesId = StableIdentity.xtreamSeries(sourceId, "62"),
                    providerSeriesId = "62",
                    categoryProviderKey = "9",
                    name = "Series",
                    posterUrl = null,
                    backdropUrl = null,
                    description = null,
                    rating = null,
                    providerOrder = 0,
                ),
            ),
        )

        val plan = CatalogReconciler.reconcile(
            sourceId = sourceId,
            sourceType = SourceType.XTREAM,
            generation = 10,
            snapshot = snapshot,
            existingCategories = listOf(
                ProviderCategoryEntity(sourceId.value, "LIVE", "legacy-live-category", "7", "Old", 0, true, 9),
                ProviderCategoryEntity(sourceId.value, "MOVIE", "legacy-movie-category", "8", "Old", 0, true, 9),
                ProviderCategoryEntity(sourceId.value, "SERIES", "legacy-series-category", "9", "Old", 0, true, 9),
            ),
            existingLiveChannels = listOf(
                LiveChannelEntity(
                    channelId = "legacy-live-id",
                    sourceId = sourceId.value,
                    providerKey = "42",
                    providerStreamId = "42",
                    categoryKey = "legacy-live-category",
                    name = "Old",
                    tvgId = null,
                    tvgName = null,
                    logoUrl = null,
                    streamLocator = "xtream://live/42",
                    providerOrder = 0,
                    available = true,
                    lastSeenGeneration = 9,
                ),
            ),
            existingMovies = listOf(
                MovieEntity(
                    movieId = "legacy-movie-id",
                    sourceId = sourceId.value,
                    providerStreamId = "52",
                    categoryKey = "legacy-movie-category",
                    name = "Old",
                    posterUrl = null,
                    backdropUrl = null,
                    extension = null,
                    rating = null,
                    providerOrder = 0,
                    available = true,
                    lastSeenGeneration = 9,
                ),
            ),
            existingSeries = listOf(
                SeriesEntity(
                    seriesId = "legacy-series-id",
                    sourceId = sourceId.value,
                    providerSeriesId = "62",
                    categoryKey = "legacy-series-category",
                    name = "Old",
                    posterUrl = null,
                    backdropUrl = null,
                    description = null,
                    rating = null,
                    providerOrder = 0,
                    available = true,
                    lastSeenGeneration = 9,
                ),
            ),
        )

        assertEquals("legacy-live-category", plan.categories.first { it.kind == "LIVE" }.categoryKey)
        assertEquals("legacy-live-id", plan.liveChannels.single().channelId)
        assertEquals("legacy-live-category", plan.liveChannels.single().categoryKey)
        assertEquals("legacy-movie-id", plan.movies.single().movieId)
        assertEquals("legacy-movie-category", plan.movies.single().categoryKey)
        assertEquals("legacy-series-id", plan.series.single().seriesId)
        assertEquals("legacy-series-category", plan.series.single().categoryKey)
    }

    private fun repository(
        sourceDao: FakeSourceDao = FakeSourceDao(),
        refreshStateDao: FakeRefreshStateDao = FakeRefreshStateDao(),
        activeSourceStore: FakeActiveSourceStore = FakeActiveSourceStore(),
        credentialStore: FakeCredentialStore = FakeCredentialStore(),
        catalogLoader: SourceCatalogLoader = FakeSourceCatalogLoader(),
        catalogRefreshStore: CatalogRefreshStore = FakeCatalogRefreshStore(refreshStateDao),
        nowMillis: () -> Long = { 1_000L },
    ): SourceRepositoryImpl {
        val ids = AtomicInteger(0)
        return SourceRepositoryImpl(
            sourceDao = sourceDao,
            refreshStateDao = refreshStateDao,
            activeSourceStore = activeSourceStore,
            credentialStore = credentialStore,
            catalogLoader = catalogLoader,
            catalogRefreshStore = catalogRefreshStore,
            nowMillis = nowMillis,
            newSourceId = { SourceId("source-${ids.incrementAndGet()}") },
        )
    }

    private fun source(
        id: String,
        enabled: Boolean,
        updatedAt: Long,
    ) = SourceEntity(
        sourceId = id,
        displayName = id,
        type = "XTREAM",
        baseLocator = "https://$id.example",
        credentialReference = id,
        enabled = enabled,
        createdAt = 1L,
        updatedAt = updatedAt,
    )
}

private class FakeSourceDao(
    initial: List<SourceEntity> = emptyList(),
) : SourceDao {
    private val rows = MutableStateFlow(sort(initial))
    var failInsert: Boolean = false
    var failUpdate: Boolean = false
    var failDelete: Boolean = false

    override fun observeAll(): Flow<List<SourceEntity>> = rows

    override suspend fun get(sourceId: String): SourceEntity? =
        rows.value.firstOrNull { it.sourceId == sourceId }

    override suspend fun getAll(): List<SourceEntity> = rows.value

    override suspend fun insert(entity: SourceEntity) {
        if (failInsert) error("insert failed")
        check(rows.value.none { it.sourceId == entity.sourceId })
        rows.value = sort(rows.value + entity)
    }

    override suspend fun update(entity: SourceEntity) {
        if (failUpdate) error("update failed")
        check(rows.value.any { it.sourceId == entity.sourceId })
        rows.value = sort(rows.value.map { row ->
            if (row.sourceId == entity.sourceId) entity else row
        })
    }

    override suspend fun delete(sourceId: String): Int {
        if (failDelete) error("delete failed")
        val before = rows.value.size
        rows.value = rows.value.filterNot { it.sourceId == sourceId }
        return before - rows.value.size
    }

    private companion object {
        fun sort(rows: List<SourceEntity>): List<SourceEntity> = rows.sortedWith(
            compareByDescending<SourceEntity> { it.updatedAt }
                .thenBy { it.createdAt }
                .thenBy { it.sourceId },
        )
    }
}

private class FakeRefreshStateDao(
    initial: List<RefreshStateEntity> = emptyList(),
) : RefreshStateDao {
    private val rows = MutableStateFlow(initial.sortedBy(RefreshStateEntity::sourceId))
    private var categories: List<ProviderCategoryEntity> = emptyList()
    private var liveChannels: List<LiveChannelEntity> = emptyList()
    private var movies: List<MovieEntity> = emptyList()
    private var series: List<SeriesEntity> = emptyList()

    override fun observeAll(): Flow<List<RefreshStateEntity>> = rows

    override suspend fun get(sourceId: String): RefreshStateEntity? =
        rows.value.firstOrNull { it.sourceId == sourceId }

    override suspend fun upsert(entity: RefreshStateEntity) {
        rows.value = (rows.value.filterNot { it.sourceId == entity.sourceId } + entity)
            .sortedBy(RefreshStateEntity::sourceId)
    }

    override suspend fun getCategoriesForRefresh(sourceId: String): List<ProviderCategoryEntity> =
        categories.filter { it.sourceId == sourceId }

    override suspend fun getLiveChannelsForRefresh(sourceId: String): List<LiveChannelEntity> =
        liveChannels.filter { it.sourceId == sourceId }

    override suspend fun getMoviesForRefresh(sourceId: String): List<MovieEntity> =
        movies.filter { it.sourceId == sourceId }

    override suspend fun getSeriesForRefresh(sourceId: String): List<SeriesEntity> =
        series.filter { it.sourceId == sourceId }

    override suspend fun upsertCategories(rows: List<ProviderCategoryEntity>) {
        val incoming = rows.associateBy { Triple(it.sourceId, it.kind, it.categoryKey) }
        categories = categories.filterNot {
            Triple(it.sourceId, it.kind, it.categoryKey) in incoming
        } + rows
    }

    override suspend fun upsertLiveChannels(rows: List<LiveChannelEntity>) {
        val incoming = rows.associateBy(LiveChannelEntity::channelId)
        liveChannels = liveChannels.filterNot { it.channelId in incoming } + rows
    }

    override suspend fun upsertMovies(rows: List<MovieEntity>) {
        val incoming = rows.associateBy(MovieEntity::movieId)
        movies = movies.filterNot { it.movieId in incoming } + rows
    }

    override suspend fun upsertSeries(rows: List<SeriesEntity>) {
        val incoming = rows.associateBy(SeriesEntity::seriesId)
        series = series.filterNot { it.seriesId in incoming } + rows
    }

    override suspend fun markMissingCategoriesUnavailable(
        sourceId: String,
        kind: String,
        generation: Long,
    ) {
        categories = categories.map {
            if (it.sourceId == sourceId && it.kind == kind && it.lastSeenGeneration != generation) {
                it.copy(available = false)
            } else {
                it
            }
        }
    }

    override suspend fun markMissingLiveUnavailable(sourceId: String, generation: Long) {
        liveChannels = liveChannels.map {
            if (it.sourceId == sourceId && it.lastSeenGeneration != generation) {
                it.copy(available = false)
            } else {
                it
            }
        }
    }

    override suspend fun markMissingMoviesUnavailable(sourceId: String, generation: Long) {
        movies = movies.map {
            if (it.sourceId == sourceId && it.lastSeenGeneration != generation) {
                it.copy(available = false)
            } else {
                it
            }
        }
    }

    override suspend fun markMissingSeriesUnavailable(sourceId: String, generation: Long) {
        series = series.map {
            if (it.sourceId == sourceId && it.lastSeenGeneration != generation) {
                it.copy(available = false)
            } else {
                it
            }
        }
    }
}

private class FakeActiveSourceStore(
    initial: String? = null,
) : ActiveSourceSelectionStore {
    private val selected = MutableStateFlow(initial)

    override val selectedSourceId: Flow<String?> = selected

    override suspend fun currentSelectedSourceId(): String? = selected.value

    override suspend fun setSelectedSourceId(sourceId: String?) {
        selected.value = sourceId
    }
}

private class FakeCredentialStore : CredentialStore {
    val secrets = linkedMapOf<SourceId, SourceSecret>()
    var putCalls: Int = 0

    override suspend fun put(sourceId: SourceId, secret: SourceSecret) {
        putCalls += 1
        secrets[sourceId] = secret
    }

    override suspend fun get(sourceId: SourceId): SourceSecret? = secrets[sourceId]

    override suspend fun delete(sourceId: SourceId) {
        secrets.remove(sourceId)
    }

    fun seed(sourceId: SourceId, secret: SourceSecret) {
        secrets[sourceId] = secret
    }
}

private class FakeSourceCatalogLoader(
    private val failure: SourceRefreshFailureCategory? = null,
) : SourceCatalogLoader {
    override suspend fun load(
        sourceId: SourceId,
        sourceType: SourceType,
        baseLocator: String,
        secret: SourceSecret,
    ): ProviderCatalogSnapshot {
        failure?.let { throw CatalogLoadException(it) }
        return ProviderCatalogSnapshot(
            sourceType = sourceType,
            categories = emptyList(),
            liveChannels = emptyList(),
            movies = emptyList(),
            series = emptyList(),
        )
    }
}

private class FakeCatalogRefreshStore(
    private val refreshStateDao: RefreshStateDao,
    private val failCommit: Boolean = false,
) : CatalogRefreshStore {
    var commitCalls: Int = 0
    var lastGeneration: Long? = null

    override suspend fun commitSuccessfulRefresh(
        sourceId: SourceId,
        sourceType: SourceType,
        generation: Long,
        snapshot: ProviderCatalogSnapshot,
        attemptAtEpochMs: Long,
        completedAtEpochMs: Long,
    ) {
        commitCalls += 1
        lastGeneration = generation
        if (failCommit) error("commit failed")
        refreshStateDao.upsert(
            RefreshStateEntity(
                sourceId = sourceId.value,
                generation = generation,
                state = "SUCCESS",
                lastAttempt = attemptAtEpochMs,
                lastSuccess = completedAtEpochMs,
                errorCode = null,
            ),
        )
    }
}
