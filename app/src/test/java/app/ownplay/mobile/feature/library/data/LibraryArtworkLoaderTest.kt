package app.ownplay.mobile.feature.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryArtworkLoaderTest {
    @Test
    fun memoryCacheUsesUrlIdentityAndLeastRecentlyUsedByteBudget() {
        val cache = LibraryArtworkMemoryCache(maxBytes = 6L)
        val first = payload("https://example.test/first.jpg", 3)
        val second = payload("https://example.test/second.jpg", 2)
        val third = payload("https://example.test/third.jpg", 3)

        cache.put(first)
        cache.put(second)
        assertSame(first, cache.get(first.cacheKey))

        cache.put(third)

        assertSame(first, cache.get(first.cacheKey))
        assertNull(cache.get(second.cacheKey))
        assertSame(third, cache.get(third.cacheKey))
        assertEquals(2, cache.entryCount())
        assertEquals(6L, cache.currentSizeBytes())
    }

    @Test
    fun memoryCacheRejectsPayloadLargerThanTotalBudget() {
        val cache = LibraryArtworkMemoryCache(maxBytes = 4L)

        cache.put(payload("https://example.test/large.jpg", 5))

        assertEquals(0, cache.entryCount())
        assertEquals(0L, cache.currentSizeBytes())
    }

    @Test
    fun replacingSameUrlUpdatesByteAccountingWithoutDuplicateEntry() {
        val cache = LibraryArtworkMemoryCache(maxBytes = 10L)
        cache.put(payload("https://example.test/poster.jpg", 3))
        val replacement = payload("https://example.test/poster.jpg", 5)

        cache.put(replacement)

        assertSame(replacement, cache.get(replacement.cacheKey))
        assertEquals(1, cache.entryCount())
        assertEquals(5L, cache.currentSizeBytes())
    }

    @Test
    fun payloadDiagnosticsRedactUrlIdentityAndDoNotPrintBytes() {
        val value = payload("https://example.test/private-token.jpg", 3)
        val diagnostic = value.toString()

        assertFalse(diagnostic.contains("private-token"))
        assertFalse(diagnostic.contains("1, 2, 3"))
        assertTrue(diagnostic.contains("cacheKey=<redacted>"))
        assertTrue(diagnostic.contains("bytes=3"))
    }

    private fun payload(url: String, size: Int): LibraryArtworkPayload =
        LibraryArtworkPayload(
            cacheKey = url,
            bytes = ByteArray(size) { (it + 1).toByte() },
            contentType = "image/jpeg",
        )
}
