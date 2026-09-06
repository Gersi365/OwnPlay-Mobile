package app.ownplay.player.persistence.reconcile

import app.ownplay.player.source.m3u.M3uEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderIdentityTest {
    @Test
    fun xtreamIdentityUsesOnlyNonSensitiveStreamId() {
        assertEquals("xtream:live:42", ProviderIdentity.xtreamLiveStream(42))
    }

    @Test
    fun xtreamIdentityRequiresPositiveStreamId() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderIdentity.xtreamLiveStream(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderIdentity.xtreamLiveStream(-1)
        }
    }

    @Test
    fun m3uTvgIdIdentityIgnoresChangingTokenizedStreamUrl() {
        val first = M3uEntry(
            displayName = "News HD",
            streamUrl = "https://example.test/live.m3u8?token=secret-one",
            tvgId = "NEWS.ONE",
        )
        val refreshed = first.copy(
            streamUrl = "https://example.test/live.m3u8?token=secret-two",
        )

        assertEquals(ProviderIdentity.m3u(first), ProviderIdentity.m3u(refreshed))
        assertFalse(ProviderIdentity.m3u(first).contains("secret-one"))
    }

    @Test
    fun m3uMetadataFallbackIsCaseAndWhitespaceStable() {
        val first = M3uEntry(
            displayName = " News   One ",
            streamUrl = "https://example.test/a",
            tvgName = "NEWS ONE",
            groupTitle = " News ",
        )
        val refreshed = first.copy(
            displayName = "news one",
            tvgName = "news one",
            groupTitle = "news",
        )

        assertEquals(ProviderIdentity.m3u(first), ProviderIdentity.m3u(refreshed))
    }

    @Test
    fun fallbackLocatorHashDoesNotExposePathOrQuery() {
        val entry = M3uEntry(
            displayName = "Unnamed",
            streamUrl = "https://example.test/user/password/live.ts?token=super-secret",
        )
        val identity = ProviderIdentity.m3u(entry)

        assertTrue(identity.startsWith("m3u:"))
        assertFalse(identity.contains("password"))
        assertFalse(identity.contains("super-secret"))
    }

    @Test
    fun materiallyDifferentMetadataProducesDifferentIdentity() {
        val first = M3uEntry("One", "https://example.test/one", tvgId = "one")
        val second = M3uEntry("Two", "https://example.test/two", tvgId = "two")

        assertNotEquals(ProviderIdentity.m3u(first), ProviderIdentity.m3u(second))
    }
}
