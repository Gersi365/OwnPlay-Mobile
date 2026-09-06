package app.ownplay.player.vod

import app.ownplay.player.source.SOURCE_REFRESH_STALE_MILLIS
import app.ownplay.player.source.SourceError
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

class VodMovieDetailsCacheTest {
    @Test
    fun freshDetailsSkipProviderRefresh() = runBlocking {
        var now = 10_000L
        val cache = VodMovieDetailsCache(this) { now }
        val calls = AtomicInteger()
        val firstDetails = details("First")

        val first = cache.refreshIfStale("source-a", "movie-a") {
            calls.incrementAndGet()
            SourceResult.Success(firstDetails)
        }
        now += 1_000L
        val second = cache.refreshIfStale("source-a", "movie-a") {
            calls.incrementAndGet()
            SourceResult.Success(details("Unexpected"))
        }

        assertEquals(SourceResult.Success(firstDetails), first)
        assertEquals(SourceResult.Success(firstDetails), second)
        assertEquals(firstDetails, cache.peek("source-a", "movie-a"))
        assertEquals(1, calls.get())
    }

    @Test
    fun staleDetailsRefreshAndReplaceCachedValue() = runBlocking {
        var now = 20_000L
        val cache = VodMovieDetailsCache(this) { now }
        val calls = AtomicInteger()
        val firstDetails = details("First")
        val refreshedDetails = details("Refreshed")

        cache.refreshIfStale("source-a", "movie-a") {
            calls.incrementAndGet()
            SourceResult.Success(firstDetails)
        }
        now += SOURCE_REFRESH_STALE_MILLIS
        val refreshed = cache.refreshIfStale("source-a", "movie-a") {
            calls.incrementAndGet()
            SourceResult.Success(refreshedDetails)
        }

        assertEquals(SourceResult.Success(refreshedDetails), refreshed)
        assertEquals(refreshedDetails, cache.peek("source-a", "movie-a"))
        assertEquals(2, calls.get())
    }

    @Test
    fun clockRollbackTreatsDetailsAsStale() = runBlocking {
        var now = 30_000L
        val cache = VodMovieDetailsCache(this) { now }
        val calls = AtomicInteger()

        cache.refreshIfStale("source-a", "movie-a") {
            calls.incrementAndGet()
            SourceResult.Success(details("First"))
        }
        now = 29_000L
        cache.refreshIfStale("source-a", "movie-a") {
            calls.incrementAndGet()
            SourceResult.Success(details("After rollback"))
        }

        assertEquals(2, calls.get())
    }

    @Test
    fun sameMovieSharesInFlightProviderRefresh() = runBlocking {
        val cache = VodMovieDetailsCache(this)
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sharedDetails = details("Shared")

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            cache.refreshIfStale("source-a", "movie-a") {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                SourceResult.Success(sharedDetails)
            }
        }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            cache.refreshIfStale("source-a", "movie-a") {
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
    fun differentMoviesDoNotShareInFlightProviderRefresh() = runBlocking {
        val cache = VodMovieDetailsCache(this)
        val calls = AtomicInteger()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            cache.refreshIfStale("source-a", "movie-a") {
                calls.incrementAndGet()
                firstEntered.complete(Unit)
                releaseFirst.await()
                SourceResult.Success(details("A"))
            }
        }
        firstEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            cache.refreshIfStale("source-a", "movie-b") {
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
        val cache = VodMovieDetailsCache(this)
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sharedDetails = details("Shared")

        val firstCaller = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.refreshIfStale("source-a", "movie-a") {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                SourceResult.Success(sharedDetails)
            }
        }
        entered.await()
        firstCaller.cancelAndJoin()

        val secondCaller = async(start = CoroutineStart.UNDISPATCHED) {
            cache.refreshIfStale("source-a", "movie-a") {
                calls.incrementAndGet()
                SourceResult.Success(details("Unexpected"))
            }
        }

        assertEquals(1, calls.get())
        release.complete(Unit)
        assertEquals(SourceResult.Success(sharedDetails), secondCaller.await())
    }

    @Test
    fun failedStaleRefreshDoesNotOverwriteCachedDetails() = runBlocking {
        var now = 40_000L
        val cache = VodMovieDetailsCache(this) { now }
        val firstDetails = details("First")

        cache.refreshIfStale("source-a", "movie-a") {
            SourceResult.Success(firstDetails)
        }
        now += SOURCE_REFRESH_STALE_MILLIS
        val failed = cache.refreshIfStale("source-a", "movie-a") {
            SourceResult.Failure(SourceError.NetworkUnavailable)
        }

        assertEquals(SourceResult.Failure(SourceError.NetworkUnavailable), failed)
        assertEquals(firstDetails, cache.peek("source-a", "movie-a"))
    }

    private fun details(name: String): VodMovieDetails = VodMovieDetails(
        movie = VodMovie(
            movieId = name,
            providerStreamId = name.length,
            categoryKey = null,
            name = name,
            posterUrl = null,
            containerExtension = null,
            rating = null,
            addedAtEpochSeconds = null,
            isFavorite = false,
            positionMs = null,
            durationMs = null,
            progressCompleted = false,
            progressUpdatedAtEpochMillis = null,
        ),
        originalName = null,
        description = null,
        posterUrl = null,
        backdropUrls = emptyList(),
        releaseDate = null,
        durationSeconds = null,
        durationLabel = null,
        genre = null,
        country = null,
        director = null,
        cast = null,
        rating = null,
        youtubeTrailer = null,
    )
}
