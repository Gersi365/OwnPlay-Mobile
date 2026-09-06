package app.ownplay.player.source.xtream

import app.ownplay.player.source.CredentialRef
import app.ownplay.player.source.PlaylistSource
import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceResult
import app.ownplay.player.source.credential.CredentialStore
import app.ownplay.player.source.credential.XtreamCredentials
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XtreamSourceVerifierTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun validate_resolvesOpaqueCredentialReferenceAtCallTime() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"user_info":{"auth":1,"status":"Active"}}""")
                .build(),
        )
        val ref = CredentialRef("fixture-ref")
        val store = FakeCredentialStore(
            values = mapOf(ref to XtreamCredentials("fixture-user", "fixture-password")),
        )
        val verifier = XtreamSourceVerifier(
            credentialStore = store,
            client = XtreamClient(allowCleartext = true),
        )
        val source = PlaylistSource.Xtream(
            name = "Fixture",
            serverUrl = server.url("/").toString(),
            credentialRef = ref,
        )

        val result = verifier.validate(source)

        assertTrue(result is SourceResult.Success)
        assertEquals("Active", (result as SourceResult.Success).value.status)
        val request = server.takeRequest()
        assertEquals("fixture-user", request.url.queryParameter("username"))
        assertEquals("fixture-password", request.url.queryParameter("password"))
    }

    @Test
    fun validate_missingCredentialReference_isDeterministicFailure() = runBlocking {
        val verifier = XtreamSourceVerifier(
            credentialStore = FakeCredentialStore(emptyMap()),
            client = XtreamClient(allowCleartext = true),
        )
        val source = PlaylistSource.Xtream(
            name = "Missing",
            serverUrl = server.url("/").toString(),
            credentialRef = CredentialRef("missing-ref"),
        )

        val result = verifier.validate(source)

        assertEquals(SourceResult.Failure(SourceError.CredentialUnavailable), result)
    }

    @Test
    fun validate_unreadableCredentialPayload_isDeterministicFailure() = runBlocking {
        val verifier = XtreamSourceVerifier(
            credentialStore = object : CredentialStore {
                override fun put(credentials: XtreamCredentials): CredentialRef = error("unused")

                override fun get(ref: CredentialRef): XtreamCredentials? {
                    throw GeneralSecurityException("fixture corruption")
                }

                override fun delete(ref: CredentialRef) = Unit
            },
            client = XtreamClient(allowCleartext = true),
        )
        val source = PlaylistSource.Xtream(
            name = "Unreadable",
            serverUrl = server.url("/").toString(),
            credentialRef = CredentialRef("unreadable-ref"),
        )

        val result = verifier.validate(source)

        assertEquals(SourceResult.Failure(SourceError.CredentialUnavailable), result)
    }

    @Test
    fun validate_propagatesCredentialStoreCancellation() {
        val verifier = XtreamSourceVerifier(
            credentialStore = object : CredentialStore {
                override fun put(credentials: XtreamCredentials): CredentialRef = error("unused")

                override fun get(ref: CredentialRef): XtreamCredentials? {
                    throw CancellationException("cancel verification")
                }

                override fun delete(ref: CredentialRef) = Unit
            },
            client = XtreamClient(allowCleartext = true),
        )
        val source = PlaylistSource.Xtream(
            name = "Cancelled",
            serverUrl = server.url("/").toString(),
            credentialRef = CredentialRef("cancelled-ref"),
        )

        val failure = runCatching {
            runBlocking { verifier.validate(source) }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    private class FakeCredentialStore(
        private val values: Map<CredentialRef, XtreamCredentials>,
    ) : CredentialStore {
        override fun put(credentials: XtreamCredentials): CredentialRef = error("unused")

        override fun get(ref: CredentialRef): XtreamCredentials? = values[ref]

        override fun delete(ref: CredentialRef) = Unit
    }
}
