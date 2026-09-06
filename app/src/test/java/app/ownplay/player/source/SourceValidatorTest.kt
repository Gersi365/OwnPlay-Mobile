package app.ownplay.player.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceValidatorTest {
    @Test
    fun xtreamServer_normalizesTrailingSlash() {
        val result = SourceValidator.validateXtreamServer(" https://example.com/base ")

        assertTrue(result is UrlValidationResult.Valid)
        val valid = result as UrlValidationResult.Valid
        assertEquals("https://example.com/base/", valid.normalizedUrl)
        assertFalse(valid.usesCleartext)
    }

    @Test
    fun xtreamServer_rejectsEmbeddedCredentials() {
        val result = SourceValidator.validateXtreamServer("https://user:secret@example.com")

        assertEquals(
            UrlValidationResult.Invalid(SourceError.EmbeddedCredentialsNotAllowed),
            result,
        )
    }

    @Test
    fun xtreamServer_rejectsQueryComponents() {
        val result = SourceValidator.validateXtreamServer("https://example.com?token=secret")

        assertEquals(
            UrlValidationResult.Invalid(SourceError.UnexpectedUrlComponent),
            result,
        )
    }

    @Test
    fun remoteUrls_rejectInvalidPortsBeforeNetworkAccess() {
        listOf(
            "https://example.com:0",
            "https://example.com:70000",
        ).forEach { url ->
            assertEquals(
                UrlValidationResult.Invalid(SourceError.InvalidUrl),
                SourceValidator.validateXtreamServer(url),
            )
        }
        assertEquals(
            UrlValidationResult.Invalid(SourceError.InvalidUrl),
            SourceValidator.validateRemotePlaylistUrl("https://example.com:70000/list.m3u"),
        )
    }

    @Test
    fun remotePlaylist_allowsQueryAndFlagsCleartext() {
        val result = SourceValidator.validateRemotePlaylistUrl(
            "http://example.com/playlist.m3u?token=opaque",
        )

        assertTrue(result is UrlValidationResult.Valid)
        val valid = result as UrlValidationResult.Valid
        assertEquals("http://example.com/playlist.m3u?token=opaque", valid.normalizedUrl)
        assertTrue(valid.usesCleartext)
    }

    @Test
    fun localDocument_requiresHierarchicalContentUriWithAuthority() {
        assertNull(SourceValidator.validateLocalDocumentUri("content://media/document/42"))
        assertEquals(
            SourceError.UnsupportedLocalUri,
            SourceValidator.validateLocalDocumentUri("content:/document/42"),
        )
        assertEquals(
            SourceError.UnsupportedLocalUri,
            SourceValidator.validateLocalDocumentUri("file:///storage/emulated/0/list.m3u"),
        )
    }
}
