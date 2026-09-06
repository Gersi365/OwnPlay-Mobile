package app.ownplay.player.playback

import app.ownplay.player.persistence.secure.SensitiveValueRef
import app.ownplay.player.persistence.secure.SensitiveValueStore
import app.ownplay.player.source.CredentialRef
import app.ownplay.player.source.credential.CredentialStore
import app.ownplay.player.source.credential.XtreamCredentials
import app.ownplay.player.source.xtream.XtreamSourceLocator
import app.ownplay.player.source.xtream.XtreamSourceLocatorCodec
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesPlaybackResolutionTest {
    @Test
    fun resolveSeriesEpisode_buildsXtreamSeriesUrlUsingLateBoundCredentials() = runBlocking {
        val resolver = resolver()

        val result = resolver.resolve(
            PlaybackRequest(
                sourceId = "source-1",
                channelId = "source-1:series:501:episode:1001",
                mediaKind = PlaybackMediaKind.SERIES_EPISODE,
                providerStreamId = 1001,
                containerExtension = "mkv",
            ),
        )

        assertTrue(result is PlaybackResolutionResult.Success)
        val locator = (result as PlaybackResolutionResult.Success).locator
        assertEquals(ResolvedPlaybackOrigin.XTREAM_SERIES, locator.origin)
        assertEquals(
            "https://provider.example/series/fixture-user/fixture-password/1001.mkv",
            locator.value,
        )
    }

    @Test
    fun resolveSeriesEpisode_normalizesExtensionIndependentlyOfDeviceLocale() = runBlocking {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val result = resolver().resolve(
                PlaybackRequest(
                    sourceId = "source-1",
                    channelId = "source-1:series:501:episode:1001",
                    mediaKind = PlaybackMediaKind.SERIES_EPISODE,
                    providerStreamId = 1001,
                    containerExtension = "AVI",
                ),
            )

            assertTrue(result is PlaybackResolutionResult.Success)
            val locator = (result as PlaybackResolutionResult.Success).locator
            assertEquals(
                "https://provider.example/series/fixture-user/fixture-password/1001.avi",
                locator.value,
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun seriesEpisodeRequest_requiresPositiveProviderStreamId() {
        val failure = runCatching {
            PlaybackRequest(
                sourceId = "source-1",
                channelId = "episode-1",
                mediaKind = PlaybackMediaKind.SERIES_EPISODE,
                providerStreamId = 0,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun resolver(): LivePlaybackResolver {
        val source = PlaybackSourceRecord(
            sourceId = "source-1",
            sourceKind = PlaybackResolutionSourceKind.XTREAM,
            locatorRef = "locator-ref",
            credentialRef = "credential-ref",
            enabled = true,
        )
        return LivePlaybackResolver(
            lookup = FakeLookup(source),
            sensitiveValueStore = FakeSensitiveValueStore(
                mapOf(
                    "locator-ref" to XtreamSourceLocatorCodec.encode(
                        XtreamSourceLocator(
                            serverUrl = "https://provider.example/",
                            allowCleartext = false,
                        ),
                    ),
                ),
            ),
            credentialStore = FakeCredentialStore(
                mapOf(
                    "credential-ref" to XtreamCredentials(
                        username = "fixture-user",
                        password = "fixture-password",
                    ),
                ),
            ),
        )
    }

    private class FakeLookup(
        private val source: PlaybackSourceRecord,
    ) : PlaybackResolutionLookup {
        override suspend fun sourceById(sourceId: String): PlaybackSourceRecord? =
            source.takeIf { it.sourceId == sourceId }

        override suspend fun channelById(channelId: String): PlaybackChannelRecord? = null

        override suspend fun movieById(movieId: String): PlaybackMovieRecord? = null
    }

    private class FakeSensitiveValueStore(
        private val values: Map<String, String>,
    ) : SensitiveValueStore {
        override fun put(value: String): SensitiveValueRef = error("Not used")

        override fun get(ref: SensitiveValueRef): String? = values[ref.value]

        override fun delete(ref: SensitiveValueRef) = Unit
    }

    private class FakeCredentialStore(
        private val credentials: Map<String, XtreamCredentials>,
    ) : CredentialStore {
        override fun put(credentials: XtreamCredentials): CredentialRef = error("Not used")

        override fun get(ref: CredentialRef): XtreamCredentials? = credentials[ref.value]

        override fun delete(ref: CredentialRef) = Unit
    }
}
