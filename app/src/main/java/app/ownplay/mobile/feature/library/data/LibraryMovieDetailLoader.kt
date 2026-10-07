package app.ownplay.mobile.feature.library.data

import app.ownplay.mobile.data.db.LibraryDao
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.data.security.CredentialStore
import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.feature.library.domain.LibraryMovieDetailLoadResult
import app.ownplay.mobile.feature.library.domain.LibraryMovieDetailMetadata
import app.ownplay.mobile.sources.data.xtream.XtreamClient
import app.ownplay.mobile.sources.data.xtream.XtreamConnection
import app.ownplay.mobile.sources.data.xtream.XtreamMovieDetail
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceType
import kotlinx.coroutines.CancellationException

internal interface LibraryMovieDetailLoader {
    suspend fun load(
        sourceId: SourceId,
        movieId: String,
    ): LibraryMovieDetailLoadResult
}

internal class SourceBackedLibraryMovieDetailLoader(
    private val sourceDao: SourceDao,
    private val libraryDao: LibraryDao,
    private val credentialStore: CredentialStore,
    private val xtreamClient: XtreamClient,
) : LibraryMovieDetailLoader {
    override suspend fun load(
        sourceId: SourceId,
        movieId: String,
    ): LibraryMovieDetailLoadResult {
        if (movieId.isBlank()) return LibraryMovieDetailLoadResult.Unavailable
        val source = sourceDao.get(sourceId.value)
            ?: return LibraryMovieDetailLoadResult.Unavailable
        if (!source.enabled) return LibraryMovieDetailLoadResult.Unavailable
        val movie = libraryDao.getAvailableMovie(sourceId.value, movieId)
            ?: return LibraryMovieDetailLoadResult.Unavailable

        val sourceType = runCatching { SourceType.valueOf(source.type) }.getOrNull()
            ?: return LibraryMovieDetailLoadResult.Failed
        if (sourceType != SourceType.XTREAM) {
            return LibraryMovieDetailLoadResult.UnsupportedSource
        }

        val secret = credentialStore.get(sourceId) as? SourceSecret.Xtream
            ?: return LibraryMovieDetailLoadResult.Failed

        return try {
            val detail = xtreamClient.movieInfo(
                connection = XtreamConnection(
                    baseUrl = source.baseLocator,
                    username = secret.username,
                    password = secret.password,
                ),
                movieId = movie.providerStreamId,
            )
            LibraryMovieDetailLoadResult.Loaded(
                LibraryMovieDetailMapper.metadata(detail),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LibraryMovieDetailLoadResult.Failed
        }
    }
}

internal object LibraryMovieDetailMapper {
    fun metadata(detail: XtreamMovieDetail): LibraryMovieDetailMetadata =
        LibraryMovieDetailMetadata(
            posterUrl = detail.posterUrl,
            backdropUrl = detail.backdropUrl,
            plot = detail.plot,
            releaseDate = detail.releaseDate,
            year = detail.year,
            runtimeMs = detail.runtimeMs,
            rating = detail.rating,
            cast = detail.cast,
        )
}
