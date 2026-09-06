package app.ownplay.player.playback

import app.ownplay.player.persistence.secure.SensitiveValueRef
import app.ownplay.player.persistence.secure.SensitiveValueStore
import app.ownplay.player.source.CredentialRef
import app.ownplay.player.source.credential.CredentialStore
import app.ownplay.player.source.credential.XtreamCredentials
import app.ownplay.player.source.m3u.M3uSourceLocator
import app.ownplay.player.source.m3u.M3uSourceLocatorCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class M3uCleartextPlaybackResolutionTest {
    @Test
    fun optedInM3uSourceAllowsHttpDirectStream() = runBlocking {
        val sourceLocator = M3uSourceLocatorCodec.encode(
            M3uSourceLocator(
                endpoint = "http://provider.example/playlist.m3u",
                allowCleartext = true,
                epgUrls = emptyList(),
            ),
        )
        val resolver = resolver(
            sourceLocator = sourceLocator,
            streamLocator = "ownplay-locator-v1|direct|http://stream.example/live.m3u8",
        )

        val result = resolver.resolve(PlaybackRequest("source", "channel"))

        assertEquals(
            PlaybackResolutionResult.Success(
                ResolvedPlaybackLocator(
                    value = "http://stream.example/live.m3u8",
                    origin = ResolvedPlaybackOrigin.DIRECT,
                ),
            ),
            result,
        )
    }

    @Test
    fun m3uSourceWithoutOptInStillBlocksHttpDirectStream() = runBlocking {
        val sourceLocator = M3uSourceLocatorCodec.encode(
            M3uSourceLocator(
                endpoint = "https://provider.example/playlist.m3u",
                allowCleartext = false,
                epgUrls = emptyList(),
            ),
        )
        val resolver = resolver(
            sourceLocator = sourceLocator,
            streamLocator = "ownplay-locator-v1|direct|http://stream.example/live.m3u8",
        )

        assertEquals(
            PlaybackResolutionResult.Failure(
                PlaybackResolutionFailureReason.CLEARTEXT_NOT_ALLOWED,
            ),
            resolver.resolve(PlaybackRequest("source", "channel")),
        )
    }

    @Test
    fun legacyM3uSourceDefaultsToBlockingHttpDirectStream() = runBlocking {
        val resolver = resolver(
            sourceLocator = "https://provider.example/playlist.m3u",
            streamLocator = "ownplay-locator-v1|direct|http://stream.example/live.m3u8",
        )

        assertEquals(
            PlaybackResolutionResult.Failure(
                PlaybackResolutionFailureReason.CLEARTEXT_NOT_ALLOWED,
            ),
            resolver.resolve(PlaybackRequest("source", "channel")),
        )
    }

    private fun resolver(
        sourceLocator: String,
        streamLocator: String,
    ): LivePlaybackResolver {
        val lookup = object : PlaybackResolutionLookup {
            override suspend fun sourceById(sourceId: String): PlaybackSourceRecord =
                PlaybackSourceRecord(
                    sourceId = "source",
                    sourceKind = PlaybackResolutionSourceKind.M3U,
                    locatorRef = "source-ref",
                    credentialRef = null,
                    enabled = true,
                )

            override suspend fun channelById(channelId: String): PlaybackChannelRecord =
                PlaybackChannelRecord(
                    channelId = "channel",
                    sourceId = "source",
                    streamLocatorRef = "stream-ref",
                    removed = false,
                )

            override suspend fun movieById(movieId: String): PlaybackMovieRecord? = null
        }
        val sensitiveStore = MapSensitiveStore(
            mapOf(
                "source-ref" to sourceLocator,
                "stream-ref" to streamLocator,
            ),
        )
        return LivePlaybackResolver(
            lookup = lookup,
            sensitiveValueStore = sensitiveStore,
            credentialStore = NoopCredentialStore,
        )
    }

    private class MapSensitiveStore(
        private val values: Map<String, String>,
    ) : SensitiveValueStore {
        override fun put(value: String): SensitiveValueRef = error("not used")

        override fun get(ref: SensitiveValueRef): String? = values[ref.value]

        override fun delete(ref: SensitiveValueRef) = Unit
    }

    private object NoopCredentialStore : CredentialStore {
        override fun put(credentials: XtreamCredentials): CredentialRef = error("not used")

        override fun get(ref: CredentialRef): XtreamCredentials? = null

        override fun delete(ref: CredentialRef) = Unit
    }
}
