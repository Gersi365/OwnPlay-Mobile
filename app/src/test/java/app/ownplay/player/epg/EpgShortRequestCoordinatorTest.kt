package app.ownplay.player.epg

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class EpgShortRequestCoordinatorTest {
    @Test
    fun sameSourceChannelAndGenerationSharesInFlightLoad() = runBlocking {
        val coordinator = EpgShortRequestCoordinator(this)
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce("source-a", "channel-1", generation = 4L) {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                listOf(program("first"))
            }
        }
        entered.await()

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce("source-a", "channel-1", generation = 4L) {
                calls.incrementAndGet()
                listOf(program("second"))
            }
        }

        assertEquals(1, calls.get())
        release.complete(Unit)
        assertEquals(listOf(program("first")), first.await())
        assertEquals(listOf(program("first")), second.await())
    }

    @Test
    fun differentChannelsDoNotShareInFlightLoad() = runBlocking {
        val coordinator = EpgShortRequestCoordinator(this)
        val calls = AtomicInteger()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce("source-a", "channel-1", generation = 2L) {
                calls.incrementAndGet()
                firstEntered.complete(Unit)
                releaseFirst.await()
                listOf(program("one"))
            }
        }
        firstEntered.await()

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce("source-a", "channel-2", generation = 2L) {
                calls.incrementAndGet()
                listOf(program("two"))
            }
        }

        assertEquals(listOf(program("two")), second.await())
        assertEquals(2, calls.get())
        releaseFirst.complete(Unit)
        assertEquals(listOf(program("one")), first.await())
    }

    @Test
    fun newGenerationDoesNotJoinStaleInFlightLoad() = runBlocking {
        val coordinator = EpgShortRequestCoordinator(this)
        val calls = AtomicInteger()
        val staleEntered = CompletableDeferred<Unit>()
        val releaseStale = CompletableDeferred<Unit>()

        val stale = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce("source-a", "channel-1", generation = 8L) {
                calls.incrementAndGet()
                staleEntered.complete(Unit)
                releaseStale.await()
                listOf(program("stale"))
            }
        }
        staleEntered.await()

        val current = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce("source-a", "channel-1", generation = 9L) {
                calls.incrementAndGet()
                listOf(program("current"))
            }
        }

        assertEquals(listOf(program("current")), current.await())
        assertEquals(2, calls.get())
        releaseStale.complete(Unit)
        assertEquals(listOf(program("stale")), stale.await())
    }

    @Test
    fun completedLoadDoesNotRemainCached() = runBlocking {
        val coordinator = EpgShortRequestCoordinator(this)
        val calls = AtomicInteger()

        val first = coordinator.coalesce("source-a", "channel-1", generation = 1L) {
            calls.incrementAndGet()
            listOf(program("one"))
        }
        val second = coordinator.coalesce("source-a", "channel-1", generation = 1L) {
            calls.incrementAndGet()
            listOf(program("two"))
        }

        assertEquals(listOf(program("one")), first)
        assertEquals(listOf(program("two")), second)
        assertEquals(2, calls.get())
    }

    @Test
    fun failedLoadReleasesSlotForRetry() = runBlocking {
        supervisorScope {
            val coordinator = EpgShortRequestCoordinator(this)
            val calls = AtomicInteger()

            try {
                coordinator.coalesce("source-a", "channel-1", generation = 3L) {
                    calls.incrementAndGet()
                    error("boom")
                }
                fail("Expected the first load to fail")
            } catch (_: IllegalStateException) {
                Unit
            }

            val retry = coordinator.coalesce("source-a", "channel-1", generation = 3L) {
                calls.incrementAndGet()
                listOf(program("retry"))
            }

            assertEquals(listOf(program("retry")), retry)
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun callerCancellationDoesNotCancelSharedLoad() = runBlocking {
        val coordinator = EpgShortRequestCoordinator(this)
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val firstCaller = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce("source-a", "channel-1", generation = 5L) {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                listOf(program("shared"))
            }
        }
        entered.await()
        firstCaller.cancelAndJoin()

        val secondCaller = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce("source-a", "channel-1", generation = 5L) {
                calls.incrementAndGet()
                listOf(program("duplicate"))
            }
        }

        assertEquals(1, calls.get())
        release.complete(Unit)
        assertEquals(listOf(program("shared")), secondCaller.await())
    }

    private fun program(title: String) = EpgProgram(
        title = title,
        description = null,
        startEpochSeconds = null,
        endEpochSeconds = null,
        startLabel = null,
        endLabel = null,
    )
}
