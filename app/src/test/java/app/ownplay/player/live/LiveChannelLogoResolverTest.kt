package app.ownplay.player.live

import app.ownplay.player.persistence.secure.SensitiveValueRef
import app.ownplay.player.persistence.secure.SensitiveValueStore
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Provider channel logos are persisted as opaque secure-store references, not direct URLs.
class LiveChannelLogoResolverTest {
    @Test
    fun opaqueLogoRefResolvesStoredValue() = runBlocking {
        val store = FakeSensitiveValueStore(
            values = mapOf("logo-ref" to " https://example.com/logo.png "),
        )
        val resolver = LiveChannelLogoResolver(store)

        assertEquals(
            "https://example.com/logo.png",
            resolver.resolve("logo-ref"),
        )
        assertEquals(listOf("logo-ref"), store.readRefs)
    }

    @Test
    fun blankLogoRefDoesNotReadSecureStore() = runBlocking {
        val store = FakeSensitiveValueStore(values = emptyMap())
        val resolver = LiveChannelLogoResolver(store)

        assertNull(resolver.resolve("   "))
        assertTrue(store.readRefs.isEmpty())
    }

    @Test
    fun secureStoreFailureFallsBackToNoLogo() = runBlocking {
        val store = FakeSensitiveValueStore(
            values = emptyMap(),
            readFailure = IllegalStateException("unavailable"),
        )
        val resolver = LiveChannelLogoResolver(store)

        assertNull(resolver.resolve("logo-ref"))
        assertEquals(listOf("logo-ref"), store.readRefs)
    }

    @Test
    fun cancellationIsNotSwallowed() = runBlocking {
        val store = FakeSensitiveValueStore(
            values = emptyMap(),
            readFailure = CancellationException("cancelled"),
        )
        val resolver = LiveChannelLogoResolver(store)
        var propagated = false

        try {
            resolver.resolve("logo-ref")
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
        assertFalse(store.readRefs.isEmpty())
    }
}

private class FakeSensitiveValueStore(
    private val values: Map<String, String>,
    private val readFailure: Exception? = null,
) : SensitiveValueStore {
    val readRefs = mutableListOf<String>()

    override fun put(value: String): SensitiveValueRef = SensitiveValueRef("unused")

    override fun get(ref: SensitiveValueRef): String? {
        readRefs += ref.value
        readFailure?.let { throw it }
        return values[ref.value]
    }

    override fun delete(ref: SensitiveValueRef) = Unit
}
