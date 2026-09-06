package app.ownplay.player.playback

import app.ownplay.player.persistence.secure.SensitiveValueRef
import app.ownplay.player.persistence.secure.SensitiveValueStore
import app.ownplay.player.source.CredentialRef
import app.ownplay.player.source.credential.CredentialStore
import app.ownplay.player.source.credential.XtreamCredentials
import app.ownplay.player.source.xtream.XtreamSourceLocator
import app.ownplay.player.source.xtream.XtreamSourceLocatorCodec
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResolutionTest {
    @Test
    fun directDescriptorResolvesLateAndRedactsLocatorRendering() = runBlocking {
        val secretLocator = "https://stream.example.test/live.m3u8?token=stream-secret"
        val stores = FixtureStores(
            sensitiveValues = mapOf("stream-ref" to "ownplay-locator-v1|direct|$secretLocator"),
        )
        val resolver = resolver(
            source = source(kind = PlaybackResolutionSourceKind.OTHER),
            channel = channel(),
            stores = stores,
        )

        val result = resolver.resolve(PlaybackRequest(sourceId = "source", channelId = "channel"))
        val success = result as PlaybackResolutionResult.Success

        assertEquals(secretLocator, success.locator.value)
        assertEquals(ResolvedPlaybackOrigin.DIRECT, success.locator.origin)
        assertFalse(success.toString().contains("stream-secret"))
        assertFalse(success.locator.toString().contains(secretLocator))
        assertEquals(0, stores.credentialGetCount)
    }

    @Test
    fun channelOwnershipMismatchFailsBeforeSecureLookup() = runBlocking {
        val stores = FixtureStores(
            sensitiveValues = mapOf("stream-ref" to "ownplay-locator-v1|direct|https://example.test/live"),
        )
        val resolver = resolver(
            source = source(),
            channel = channel(sourceId = "different-source"),
            stores = stores,
        )

        assertEquals(
            PlaybackResolutionResult.Failure(PlaybackResolutionFailureReason.SOURCE_CHANNEL_MISMATCH),
            resolver.resolve(PlaybackRequest(sourceId = "source", channelId = "channel")),
        )
        assertEquals(0, stores.sensitiveGetCount)
    }

    @Test
    fun removedChannelDoesNotResolveDescriptor() = runBlocking {
        val stores = FixtureStores()
        val resolver = resolver(
            source = source(),
            channel = channel(removed = true),
            stores = stores,
        )

        assertEquals(
            PlaybackResolutionResult.Failure(PlaybackResolutionFailureReason.CHANNEL_REMOVED),
            resolver.resolve(PlaybackRequest(sourceId = "source", channelId = "channel")),
        )
        assertEquals(0, stores.sensitiveGetCount)
    }

    @Test
    fun xtreamDescriptorResolvesServerAndCredentialsOnlyAtBoundary() = runBlocking {
        val stores = FixtureStores(
            sensitiveValues = mapOf(
                "stream-ref" to "ownplay-locator-v1|xtream-live|42",
                "source-ref" to "https://provider.example.test/base",
            ),
            credentials = mapOf(
                "credential-ref" to XtreamCredentials(
                    username = "fixture-user-secret",
                    password = "fixture-pass-secret",
                ),
            ),
        )
        val resolver = resolver(
            source = source(kind = PlaybackResolutionSourceKind.XTREAM),
            channel = channel(),
            stores = stores,
        )

        val result = resolver.resolve(PlaybackRequest(sourceId = "source", channelId = "channel"))
        val success = result as PlaybackResolutionResult.Success

        assertEquals(
            "https://provider.example.test/base/live/fixture-user-secret/fixture-pass-secret/42.ts",
            success.locator.value,
        )
        assertEquals(ResolvedPlaybackOrigin.XTREAM_LIVE, success.locator.origin)
        assertFalse(success.toString().contains("fixture-user-secret"))
        assertFalse(success.toString().contains("fixture-pass-secret"))
        assertFalse(success.toString().contains("provider.example.test"))
        assertEquals(2, stores.sensitiveGetCount)
        assertEquals(1, stores.credentialGetCount)
    }

    @Test
    fun xtreamDescriptorRequiresXtreamSourceKind() = runBlocking {
        val stores = FixtureStores(
            sensitiveValues = mapOf("stream-ref" to "ownplay-locator-v1|xtream-live|42"),
        )
        val resolver = resolver(
            source = source(kind = PlaybackResolutionSourceKind.OTHER),
            channel = channel(),
            stores = stores,
        )

        assertEquals(
            PlaybackResolutionResult.Failure(PlaybackResolutionFailureReason.UNSUPPORTED_SOURCE_KIND),
            resolver.resolve(PlaybackRequest(sourceId = "source", channelId = "channel")),
        )
        assertEquals(1, stores.sensitiveGetCount)
        assertEquals(0, stores.credentialGetCount)
    }

    @Test
    fun cleartextDirectAndXtreamLocatorsRequireExplicitOptIn() = runBlocking {
        val directStores = FixtureStores(
            sensitiveValues = mapOf(
                "stream-ref" to "ownplay-locator-v1|direct|http://stream.example.test/live",
            ),
        )
        assertEquals(
            PlaybackResolutionResult.Failure(PlaybackResolutionFailureReason.CLEARTEXT_NOT_ALLOWED),
            resolver(source(), channel(), directStores).resolve(
                PlaybackRequest(sourceId = "source", channelId = "channel"),
            ),
        )

        val xtreamStores = FixtureStores(
            sensitiveValues = mapOf(
                "stream-ref" to "ownplay-locator-v1|xtream-live|7",
                "source-ref" to "http://provider.example.test",
            ),
            credentials = mapOf(
                "credential-ref" to XtreamCredentials("user", "pass"),
            ),
        )
        assertEquals(
            PlaybackResolutionResult.Failure(PlaybackResolutionFailureReason.CLEARTEXT_NOT_ALLOWED),
            resolver(
                source(kind = PlaybackResolutionSourceKind.XTREAM),
                channel(),
                xtreamStores,
            ).resolve(PlaybackRequest(sourceId = "source", channelId = "channel")),
        )
    }

    @Test
    fun sourceSpecificXtreamOptInAllowsHttpServerPlayback() = runBlocking {
        val sourceLocator = XtreamSourceLocatorCodec.encode(
            XtreamSourceLocator(
                serverUrl = "http://provider.example.test/base/",
                allowCleartext = true,
            ),
        )
        val stores = FixtureStores(
            sensitiveValues = mapOf(
                "stream-ref" to "ownplay-locator-v1|xtream-live|7",
                "source-ref" to sourceLocator,
            ),
            credentials = mapOf(
                "credential-ref" to XtreamCredentials("user", "pass"),
            ),
        )

        val result = resolver(
            source(kind = PlaybackResolutionSourceKind.XTREAM),
            channel(),
            stores,
        ).resolve(PlaybackRequest(sourceId = "source", channelId = "channel"))

        val success = result as PlaybackResolutionResult.Success
        assertEquals(
            "http://provider.example.test/base/live/user/pass/7.ts",
            success.locator.value,
        )
        assertEquals(ResolvedPlaybackOrigin.XTREAM_LIVE, success.locator.origin)
    }

    @Test
    fun sourceSpecificXtreamOptInAlsoAllowsHttpDirectSource() = runBlocking {
        val sourceLocator = XtreamSourceLocatorCodec.encode(
            XtreamSourceLocator(
                serverUrl = "https://provider.example.test/",
                allowCleartext = true,
            ),
        )
        val stores = FixtureStores(
            sensitiveValues = mapOf(
                "stream-ref" to "ownplay-locator-v1|direct|http://cdn.example.test/live.m3u8",
                "source-ref" to sourceLocator,
            ),
        )

        val result = resolver(
            source(kind = PlaybackResolutionSourceKind.XTREAM),
            channel(),
            stores,
        ).resolve(PlaybackRequest(sourceId = "source", channelId = "channel"))

        val success = result as PlaybackResolutionResult.Success
        assertEquals("http://cdn.example.test/live.m3u8", success.locator.value)
        assertEquals(ResolvedPlaybackOrigin.DIRECT, success.locator.origin)
        assertEquals(0, stores.credentialGetCount)
    }

    @Test
    fun invalidOrMissingSensitiveDataProducesRedactedFailures() = runBlocking {
        val secret = "super-secret-payload"
        val invalidStores = FixtureStores(
            sensitiveValues = mapOf("stream-ref" to "ownplay-locator-v1|future-kind|$secret"),
        )
        val invalid = resolver(source(), channel(), invalidStores).resolve(
            PlaybackRequest(sourceId = "source", channelId = "channel"),
        )

        assertEquals(
            PlaybackResolutionResult.Failure(PlaybackResolutionFailureReason.DESCRIPTOR_INVALID),
            invalid,
        )
        assertFalse(invalid.toString().contains(secret))

        val missing = resolver(source(), channel(), FixtureStores()).resolve(
            PlaybackRequest(sourceId = "source", channelId = "channel"),
        )
        assertEquals(
            PlaybackResolutionResult.Failure(PlaybackResolutionFailureReason.DESCRIPTOR_NOT_FOUND),
            missing,
        )
    }

    @Test
    fun missingCredentialsAreNotMisclassifiedAsProviderAuthenticationFailure() = runBlocking {
        val stores = FixtureStores(
            sensitiveValues = mapOf(
                "stream-ref" to "ownplay-locator-v1|xtream-live|42",
                "source-ref" to "https://provider.example.test",
            ),
        )
        val result = resolver(
            source = source(kind = PlaybackResolutionSourceKind.XTREAM),
            channel = channel(),
            stores = stores,
        ).resolve(PlaybackRequest(sourceId = "source", channelId = "channel"))

        assertEquals(
            PlaybackResolutionResult.Failure(PlaybackResolutionFailureReason.CREDENTIALS_NOT_FOUND),
            result,
        )
    }

    @Test(expected = CancellationException::class)
    fun secureStoreCancellationPropagates() {
        runBlocking {
            val stores = FixtureStores(cancelSensitiveRef = "stream-ref")
            resolver(source(), channel(), stores).resolve(
                PlaybackRequest(sourceId = "source", channelId = "channel"),
            )
        }
    }

    @Test
    fun internalRecordsRedactOpaqueReferencesAndIds() {
        val source = PlaybackSourceRecord(
            sourceId = "source-secret",
            sourceKind = PlaybackResolutionSourceKind.XTREAM,
            locatorRef = "locator-secret",
            credentialRef = "credential-secret",
            enabled = true,
        )
        val channel = PlaybackChannelRecord(
            channelId = "channel-secret",
            sourceId = "source-secret",
            streamLocatorRef = "stream-ref-secret",
            removed = false,
        )

        val rendered = source.toString() + channel.toString()
        listOf(
            "source-secret",
            "locator-secret",
            "credential-secret",
            "channel-secret",
            "stream-ref-secret",
        ).forEach { secret -> assertFalse(rendered.contains(secret)) }
        assertTrue(rendered.contains("<opaque>"))
    }

    private fun resolver(
        source: PlaybackSourceRecord?,
        channel: PlaybackChannelRecord?,
        stores: FixtureStores,
        allowCleartext: Boolean = false,
    ) = LivePlaybackResolver(
        lookup = FakeLookup(source, channel),
        sensitiveValueStore = stores,
        credentialStore = stores,
        allowCleartext = allowCleartext,
    )

    private fun source(
        kind: PlaybackResolutionSourceKind = PlaybackResolutionSourceKind.OTHER,
    ) = PlaybackSourceRecord(
        sourceId = "source",
        sourceKind = kind,
        locatorRef = "source-ref",
        credentialRef = "credential-ref",
        enabled = true,
    )

    private fun channel(
        sourceId: String = "source",
        removed: Boolean = false,
    ) = PlaybackChannelRecord(
        channelId = "channel",
        sourceId = sourceId,
        streamLocatorRef = "stream-ref",
        removed = removed,
    )

    private class FakeLookup(
        private val source: PlaybackSourceRecord?,
        private val channel: PlaybackChannelRecord?,
    ) : PlaybackResolutionLookup {
        override suspend fun sourceById(sourceId: String): PlaybackSourceRecord? = source
        override suspend fun channelById(channelId: String): PlaybackChannelRecord? = channel
        override suspend fun movieById(movieId: String): PlaybackMovieRecord? = null
    }

    private class FixtureStores(
        private val sensitiveValues: Map<String, String> = emptyMap(),
        private val credentials: Map<String, XtreamCredentials> = emptyMap(),
        private val cancelSensitiveRef: String? = null,
    ) : SensitiveValueStore, CredentialStore {
        var sensitiveGetCount: Int = 0
            private set
        var credentialGetCount: Int = 0
            private set

        override fun put(value: String): SensitiveValueRef = error("not used")

        override fun get(ref: SensitiveValueRef): String? {
            sensitiveGetCount += 1
            if (ref.value == cancelSensitiveRef) throw CancellationException("fixture cancellation")
            return sensitiveValues[ref.value]
        }

        override fun delete(ref: SensitiveValueRef) = Unit

        override fun put(credentials: XtreamCredentials): CredentialRef = error("not used")

        override fun get(ref: CredentialRef): XtreamCredentials? {
            credentialGetCount += 1
            return credentials[ref.value]
        }

        override fun delete(ref: CredentialRef) = Unit
    }
}
