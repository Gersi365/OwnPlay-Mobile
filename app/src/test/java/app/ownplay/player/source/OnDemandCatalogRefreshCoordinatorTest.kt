package app.ownplay.player.source

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OnDemandCatalogRefreshCoordinatorTest {
    @Test
    fun sameSourceAndKindSharesInFlightRefresh() = runBlocking {
        val coordinator = OnDemandCatalogRefreshCoordinator(this)
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce(
                sourceId = "source-a",
                kind = OnDemandCatalogKind.VOD,
            ) {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                SourceResult.Success(7)
            }
        }
        entered.await()

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce(
                sourceId = "source-a",
                kind = OnDemandCatalogKind.VOD,
            ) {
                calls.incrementAndGet()
                SourceResult.Success(99)
            }
        }

        assertEquals(1, calls.get())
        release.complete(Unit)
        assertEquals(SourceResult.Success(7), first.await())
        assertEquals(SourceResult.Success(7), second.await())
    }

    @Test
    fun differentKindsDoNotShareInFlightRefresh() = runBlocking {
        val coordinator = OnDemandCatalogRefreshCoordinator(this)
        val calls = AtomicInteger()
        val vodEntered = CompletableDeferred<Unit>()
        val releaseVod = CompletableDeferred<Unit>()

        val vod = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce(
                sourceId = "source-a",
                kind = OnDemandCatalogKind.VOD,
            ) {
                calls.incrementAndGet()
                vodEntered.complete(Unit)
                releaseVod.await()
                SourceResult.Success(1)
            }
        }
        vodEntered.await()

        val series = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce(
                sourceId = "source-a",
                kind = OnDemandCatalogKind.SERIES,
            ) {
                calls.incrementAndGet()
                SourceResult.Success(2)
            }
        }

        assertEquals(SourceResult.Success(2), series.await())
        assertEquals(2, calls.get())
        releaseVod.complete(Unit)
        assertEquals(SourceResult.Success(1), vod.await())
    }

    @Test
    fun differentSourcesDoNotShareInFlightRefresh() = runBlocking {
        val coordinator = OnDemandCatalogRefreshCoordinator(this)
        val calls = AtomicInteger()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce(
                sourceId = "source-a",
                kind = OnDemandCatalogKind.VOD,
            ) {
                calls.incrementAndGet()
                firstEntered.complete(Unit)
                releaseFirst.await()
                SourceResult.Success(3)
            }
        }
        firstEntered.await()

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce(
                sourceId = "source-b",
                kind = OnDemandCatalogKind.VOD,
            ) {
                calls.incrementAndGet()
                SourceResult.Success(4)
            }
        }

        assertEquals(SourceResult.Success(4), second.await())
        assertEquals(2, calls.get())
        releaseFirst.complete(Unit)
        assertEquals(SourceResult.Success(3), first.await())
    }

    @Test
    fun completedRefreshDoesNotRemainCached() = runBlocking {
        val coordinator = OnDemandCatalogRefreshCoordinator(this)
        val calls = AtomicInteger()

        val first = coordinator.coalesce(
            sourceId = "source-a",
            kind = OnDemandCatalogKind.VOD,
        ) {
            calls.incrementAndGet()
            SourceResult.Success(5)
        }
        val second = coordinator.coalesce(
            sourceId = "source-a",
            kind = OnDemandCatalogKind.VOD,
        ) {
            calls.incrementAndGet()
            SourceResult.Success(6)
        }

        assertEquals(SourceResult.Success(5), first)
        assertEquals(SourceResult.Success(6), second)
        assertEquals(2, calls.get())
    }

    @Test
    fun callerCancellationDoesNotCancelSharedRefresh() = runBlocking {
        val coordinator = OnDemandCatalogRefreshCoordinator(this)
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val firstCaller = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce(
                sourceId = "source-a",
                kind = OnDemandCatalogKind.SERIES,
            ) {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                SourceResult.Success(8)
            }
        }
        entered.await()
        firstCaller.cancelAndJoin()

        val secondCaller = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesce(
                sourceId = "source-a",
                kind = OnDemandCatalogKind.SERIES,
            ) {
                calls.incrementAndGet()
                SourceResult.Success(100)
            }
        }

        assertEquals(1, calls.get())
        release.complete(Unit)
        assertEquals(SourceResult.Success(8), secondCaller.await())
    }
}
