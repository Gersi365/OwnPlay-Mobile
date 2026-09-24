package app.ownplay.mobile.feature.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPlaybackLocatorTest {
    @Test
    fun moviePlaybackBuildsXtreamUrlAndKeepsSecretsRedactedFromDiagnostics() {
        val media = LibraryPlaybackUriFactory.movie(
            baseUrl = "https://provider.example",
            username = "user name",
            password = "private password",
            streamId = "42",
            extension = "mp4",
        )

        requireNotNull(media)
        assertEquals(
            "https://provider.example/movie/user%20name/private%20password/42.mp4",
            media.uri,
        )
        assertFalse(media.toString().contains("private password"))
        assertFalse(media.toString().contains("provider.example"))
    }

    @Test
    fun episodePlaybackUsesSeriesPathAndHlsHintWhenAuthoritativeExtensionIsM3u8() {
        val media = LibraryPlaybackUriFactory.episode(
            baseUrl = "https://provider.example",
            username = "user",
            password = "password",
            episodeId = "7",
            extension = "m3u8",
        )

        requireNotNull(media)
        assertTrue(media.uri.endsWith("/series/user/password/7.m3u8"))
        assertEquals("application/x-mpegURL", media.mimeType)
    }

    @Test
    fun movieWithoutExtensionUsesMp4ThenMkvCompatibilityFallback() {
        val media = LibraryPlaybackUriFactory.movie(
            baseUrl = "https://provider.example",
            username = "user",
            password = "password",
            streamId = "42",
            extension = null,
        )

        requireNotNull(media)
        assertEquals(
            "https://provider.example/movie/user/password/42.mp4",
            media.uri,
        )
        requireNotNull(media.fallback)
        assertEquals(
            "https://provider.example/movie/user/password/42.mkv",
            media.fallback.uri,
        )
    }

    @Test
    fun episodeWithoutExtensionUsesMp4ThenMkvCompatibilityFallback() {
        val media = LibraryPlaybackUriFactory.episode(
            baseUrl = "https://provider.example",
            username = "user",
            password = "password",
            episodeId = "7",
            extension = null,
        )

        requireNotNull(media)
        assertEquals(
            "https://provider.example/series/user/password/7.mp4",
            media.uri,
        )
        requireNotNull(media.fallback)
        assertEquals(
            "https://provider.example/series/user/password/7.mkv",
            media.fallback.uri,
        )
    }

    @Test
    fun invalidExtensionIsRejectedInsteadOfGuessed() {
        assertNull(
            LibraryPlaybackUriFactory.movie(
                baseUrl = "https://provider.example",
                username = "user",
                password = "password",
                streamId = "42",
                extension = "../ts",
            ),
        )
    }
}
