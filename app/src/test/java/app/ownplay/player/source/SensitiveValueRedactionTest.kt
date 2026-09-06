package app.ownplay.player.source

import app.ownplay.player.source.credential.XtreamCredentials
import app.ownplay.player.source.m3u.M3uEntry
import app.ownplay.player.source.m3u.M3uPlaylist
import app.ownplay.player.source.xtream.XtreamLiveStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveValueRedactionTest {
    @Test
    fun credentialToString_redactsUsernameAndPassword() {
        val rendered = XtreamCredentials(
            username = "fixture-user-secret",
            password = "fixture-password-secret",
        ).toString()

        assertFalse(rendered.contains("fixture-user-secret"))
        assertFalse(rendered.contains("fixture-password-secret"))
        assertTrue(rendered.contains("<redacted>"))
    }

    @Test
    fun credentialReferenceToString_isOpaque() {
        val rendered = CredentialRef("fixture-credential-reference-secret").toString()

        assertFalse(rendered.contains("fixture-credential-reference-secret"))
        assertTrue(rendered.contains("<opaque>"))
    }

    @Test
    fun sourceToString_redactsUrlsAndDocumentUris() {
        val remote = PlaylistSource.RemoteM3u(
            name = "Fixture",
            playlistUrl = "https://example.com/list.m3u?token=fixture-token-secret",
            epgUrl = "https://example.com/guide.xml?token=fixture-epg-secret",
        )
        val local = PlaylistSource.LocalM3u(
            name = "Local",
            documentUri = "content://provider/private-document-id",
        )

        assertFalse(remote.toString().contains("fixture-token-secret"))
        assertFalse(remote.toString().contains("fixture-epg-secret"))
        assertFalse(local.toString().contains("private-document-id"))
    }

    @Test
    fun validationResultToString_redactsNormalizedUrl() {
        val result = SourceValidator.validateRemotePlaylistUrl(
            "https://example.com/list.m3u?token=fixture-validation-secret",
        )

        assertTrue(result is UrlValidationResult.Valid)
        assertFalse(result.toString().contains("fixture-validation-secret"))
    }

    @Test
    fun parsedMediaModels_redactTransportUrls() {
        val entry = M3uEntry(
            displayName = "Fixture",
            streamUrl = "https://stream.example/live?token=fixture-stream-secret",
            logoUrl = "https://img.example/logo?token=fixture-logo-secret",
            attributes = mapOf("private-attr" to "fixture-attribute-secret"),
        )
        val playlist = M3uPlaylist(
            entries = listOf(entry),
            epgUrls = listOf("https://epg.example/guide?token=fixture-guide-secret"),
        )
        val xtream = XtreamLiveStream(
            streamId = 1,
            name = "Fixture",
            categoryId = "1",
            iconUrl = "https://img.example/icon?token=fixture-icon-secret",
            epgChannelId = "fixture.1",
            archiveDurationDays = 0,
            directSource = "https://stream.example/direct?token=fixture-direct-secret",
        )

        val rendered = entry.toString() + playlist.toString() + xtream.toString()
        listOf(
            "fixture-stream-secret",
            "fixture-logo-secret",
            "fixture-attribute-secret",
            "fixture-guide-secret",
            "fixture-icon-secret",
            "fixture-direct-secret",
        ).forEach { secret ->
            assertFalse(rendered.contains(secret))
        }
    }
}
