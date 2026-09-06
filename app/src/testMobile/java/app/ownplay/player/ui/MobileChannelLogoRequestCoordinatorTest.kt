package app.ownplay.player.ui

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MobileChannelLogoRequestCoordinatorTest {
    @Test
    fun sameUrlSharesInFlightRequest() = runBlocking {
        val coordinator = MobileChannelLogoRequestCoordinator<String>(this)
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce("https://example.com/logo.png") {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                "first"
            }
        }
        entered.await()

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce("https://example.com/logo.png") {
                calls.incrementAndGet()
                "second"
            }
        }

        assertEquals(1, calls.get())
        release.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("first", second.await())
    }

    @Test
    fun differentUrlsDoNotShareInFlightRequest() = runBlocking {
        val coordinator = MobileChannelLogoRequestCoordinator<String>(this)
        val calls = AtomicInteger()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce("https://example.com/a.png") {
                calls.incrementAndGet()
                firstEntered.complete(Unit)
                releaseFirst.await()
                "a"
            }
        }
        firstEntered.await()

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce("https://example.com/b.png") {
                calls.incrementAndGet()
                "b"
            }
        }

        assertEquals("b", second.await())
        assertEquals(2, calls.get())
        releaseFirst.complete(Unit)
        assertEquals("a", first.await())
    }

    @Test
    fun completedRequestDoesNotRemainCached() = runBlocking {
        val coordinator = MobileChannelLogoRequestCoordinator<String>(this)
        val calls = AtomicInteger()

        val first = coordinator.coalesce("https://example.com/logo.png") {
            calls.incrementAndGet()
            "first"
        }
        val second = coordinator.coalesce("https://example.com/logo.png") {
            calls.incrementAndGet()
            "second"
        }

        assertEquals("first", first)
        assertEquals("second", second)
        assertEquals(2, calls.get())
    }

    @Test
    fun callerCancellationDoesNotCancelSharedRequest() = runBlocking {
        val coordinator = MobileChannelLogoRequestCoordinator<String>(this)
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val firstCaller = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce("https://example.com/logo.png") {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                "shared"
            }
        }
        entered.await()
        firstCaller.cancelAndJoin()

        val secondCaller = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce("https://example.com/logo.png") {
                calls.incrementAndGet()
                "replacement"
            }
        }

        assertEquals(1, calls.get())
        release.complete(Unit)
        assertEquals("shared", secondCaller.await())
    }
}
