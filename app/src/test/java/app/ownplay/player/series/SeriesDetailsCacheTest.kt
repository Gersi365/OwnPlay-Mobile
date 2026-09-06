package app.ownplay.player.series

import app.ownplay.player.source.SOURCE_REFRESH_STALE_MILLIS
import app.ownplay.player.source.SourceResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesDetailsCacheTest {
    @Test
    fun freshDetailsSkipProviderRefresh() = runBlocking {
        var now = 10_000L
        val cache = SeriesDetailsCache(this) { now }
        val calls = AtomicInteger()
        val firstDetails = details("First")

        val first = cache.refreshIfStale("source-a", "series-a") {
            calls.incrementAndGet()
            SourceResult.Success(firstDetails)
        }
        now += 1_000L
        val second = cache.refreshIfStale("source-a", "series-a") {
            calls.incrementAndGet()
            SourceResult.Success(details("Unexpected"))
        }

        assertEquals(SourceResult.Success(firstDetails), first)
        assertEquals(SourceResult.Success(firstDetails), second)
        assertEquals(firstDetails, cache.peek("source-a", "series-a"))
        assertEquals(1, calls.get())
    }

    @Test
    fun staleDetailsRefreshAndReplaceCachedValue() = runBlocking {
        var now = 20_000L
        val cache = SeriesDetailsCache(this) { now }
        val calls = AtomicInteger()
        val firstDetails = details("First")
        val refreshedDetails = details("Refreshed")

        cache.refreshIfStale("source-a", "series-a") {
            calls.incrementAndGet()
            SourceResult.Success(firstDetails)
        }
        now += SOURCE_REFRESH_STALE_MILLIS
        val refreshed = cache.refreshIfStale("source-a", "series-a") {
            calls.incrementAndGet()
            SourceResult.Success(refreshedDetails)
        }

        assertEquals(SourceResult.Success(refreshedDetails), refreshed)
        assertEquals(refreshedDetails, cache.peek("source-a", "series-a"))
        assertEquals(2, calls.get())
    }

    @Test
    fun clockRollbackTreatsDetailsAsStale() = runBlocking {
        var now = 30_000L
        val cache = SeriesDetailsCache(this) { now }
        val calls = AtomicInteger()

        cache.refreshIfStale("source-a", "series-a") {
            calls.incrementAndGet()
            SourceResult.Success(details("First"))
        }
        now = 29_000L
        cache.refreshIfStale("source-a", "series-a") {
            calls.incrementAndGet()
            SourceResult.Success(details("After rollback"))
        }

        assertEquals(2, calls.get())
    }

    @Test
    fun sameSeriesSharesInFlightProviderRefresh() = runBlocking {
        val cache = SeriesDetailsCache(this)
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sharedDetails = details("Shared")

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            cache.refreshIfStale("source-a", "series-a") {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                SourceResult.Success(sharedDetails)
            }
        }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            cache.refreshIfStale("source-a", "series-a") {
                calls.incrementAndGet()
                SourceResult.Success(details("Unexpected"))
            }
        }

        assertEquals(1, calls.get())
        release.complete(Unit)
        assertEquals(SourceResult.Success(sharedDetails), first.await())
        assertEquals(SourceResult.Success(sharedDetails), second.await())
    }

    @Test
    fun differentSeriesDoNotShareInFlightProviderRefresh() = runBlocking {
        val cache = SeriesDetailsCache(this)
        val calls = AtomicInteger()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            cache.refreshIfStale("source-a", "series-a") {
                calls.incrementAndGet()
                firstEntered.complete(Unit)
                releaseFirst.await()
                SourceResult.Success(details("A"))
            }
        }
        firstEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            cache.refreshIfStale("source-a", "series-b") {
                calls.incrementAndGet()
                SourceResult.Success(details("B"))
            }
        }

        assertEquals(SourceResult.Success(details("B")), second.await())
        assertEquals(2, calls.get())
        releaseFirst.complete(Unit)
        assertEquals(SourceResult.Success(details("A")), first.await())
    }

    @Test
    fun callerCancellationDoesNotCancelSharedDetailsRefresh() = runBlocking {
        val cache = SeriesDetailsCache(this)
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sharedDetails = details("Shared")

        val firstCaller = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.refreshIfStale("source-a", "series-a") {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                SourceResult.Success(sharedDetails)
            }
        }
        entered.await()
        firstCaller.cancelAndJoin()

        val secondCaller = async(start = CoroutineStart.UNDISPATCHED) {
            cache.refreshIfStale("source-a", "series-a") {
                calls.incrementAndGet()
                SourceResult.Success(details("Unexpected"))
            }
        }

        assertEquals(1, calls.get())
        release.complete(Unit)
        assertEquals(SourceResult.Success(sharedDetails), secondCaller.await())
    }

    private fun details(name: String): SeriesDetails = SeriesDetails(
        series = SeriesSummary(
            seriesId = name,
            providerSeriesId = name.length,
            categoryKey = null,
            name = name,
            posterUrl = null,
            description = null,
            rating = null,
            lastModifiedEpochSeconds = null,
            isFavorite = false,
        ),
        description = null,
        posterUrl = null,
        backdropUrls = emptyList(),
        releaseDate = null,
        genre = null,
        country = null,
        director = null,
        cast = null,
        rating = null,
        seasons = emptyList(),
    )
}
