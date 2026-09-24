package app.ownplay.mobile.feature.library.data

import app.ownplay.mobile.data.db.EpisodeEntity
import app.ownplay.mobile.data.db.LibraryDao
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.data.security.CredentialStore
import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.feature.library.domain.LibraryDetailRefreshResult
import app.ownplay.mobile.feature.library.domain.LibrarySeriesDetailMetadata
import app.ownplay.mobile.feature.library.domain.LibrarySeriesMetadataLoadResult
import app.ownplay.mobile.sources.data.StableIdentity
import app.ownplay.mobile.sources.data.xtream.XtreamClient
import app.ownplay.mobile.sources.data.xtream.XtreamConnection
import app.ownplay.mobile.sources.data.xtream.XtreamSeriesDetail
import app.ownplay.mobile.sources.data.xtream.XtreamSeriesEpisode
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceType
import kotlinx.coroutines.CancellationException

internal interface LibrarySeriesDetailRefresher {
    suspend fun refresh(
        sourceId: SourceId,
        seriesId: String,
    ): LibraryDetailRefreshResult

    suspend fun loadMetadata(
        sourceId: SourceId,
        seriesId: String,
    ): LibrarySeriesMetadataLoadResult
}

internal class SourceBackedLibrarySeriesDetailRefresher(
    private val sourceDao: SourceDao,
    private val libraryDao: LibraryDao,
    private val credentialStore: CredentialStore,
    private val xtreamClient: XtreamClient,
) : LibrarySeriesDetailRefresher {
    override suspend fun refresh(
        sourceId: SourceId,
        seriesId: String,
    ): LibraryDetailRefreshResult =
        when (loadMetadata(sourceId, seriesId)) {
            is LibrarySeriesMetadataLoadResult.Loaded -> LibraryDetailRefreshResult.REFRESHED
            LibrarySeriesMetadataLoadResult.Unavailable -> LibraryDetailRefreshResult.UNAVAILABLE
            LibrarySeriesMetadataLoadResult.UnsupportedSource ->
                LibraryDetailRefreshResult.UNSUPPORTED_SOURCE
            LibrarySeriesMetadataLoadResult.Failed -> LibraryDetailRefreshResult.FAILED
        }

    override suspend fun loadMetadata(
        sourceId: SourceId,
        seriesId: String,
    ): LibrarySeriesMetadataLoadResult {
        if (seriesId.isBlank()) return LibrarySeriesMetadataLoadResult.Unavailable
        val source = sourceDao.get(sourceId.value)
            ?: return LibrarySeriesMetadataLoadResult.Unavailable
        if (!source.enabled) return LibrarySeriesMetadataLoadResult.Unavailable
        val series = libraryDao.getAvailableSeries(sourceId.value, seriesId)
            ?: return LibrarySeriesMetadataLoadResult.Unavailable

        val sourceType = runCatching { SourceType.valueOf(source.type) }.getOrNull()
            ?: return LibrarySeriesMetadataLoadResult.Failed
        if (sourceType != SourceType.XTREAM) {
            return LibrarySeriesMetadataLoadResult.UnsupportedSource
        }

        val secret = credentialStore.get(sourceId) as? SourceSecret.Xtream
            ?: return LibrarySeriesMetadataLoadResult.Failed

        return try {
            val detail = xtreamClient.seriesInfo(
                connection = XtreamConnection(
                    baseUrl = source.baseLocator,
                    username = secret.username,
                    password = secret.password,
                ),
                seriesId = series.providerSeriesId,
            )
            val rows = LibrarySeriesEpisodeCacheMapper.rows(
                sourceId = sourceId,
                seriesId = series.seriesId,
                generation = series.lastSeenGeneration,
                episodes = detail.episodes,
            )
            libraryDao.reconcileSeriesEpisodes(series.seriesId, rows)
            LibrarySeriesMetadataLoadResult.Loaded(
                LibrarySeriesDetailMapper.metadata(detail),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LibrarySeriesMetadataLoadResult.Failed
        }
    }
}

internal object LibrarySeriesDetailMapper {
    fun metadata(detail: XtreamSeriesDetail): LibrarySeriesDetailMetadata =
        LibrarySeriesDetailMetadata(
            posterUrl = detail.posterUrl,
            backdropUrl = detail.backdropUrl,
            plot = detail.plot,
            releaseDate = detail.releaseDate,
            year = detail.year,
            genre = detail.genre,
            rating = detail.rating,
            cast = detail.cast,
        )
}

internal object LibrarySeriesEpisodeCacheMapper {
    fun rows(
        sourceId: SourceId,
        seriesId: String,
        generation: Long,
        episodes: List<XtreamSeriesEpisode>,
    ): List<EpisodeEntity> = episodes.map { episode ->
        EpisodeEntity(
            episodeId = StableIdentity.xtreamEpisode(sourceId, episode.providerEpisodeId),
            seriesId = seriesId,
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber,
            providerEpisodeId = episode.providerEpisodeId,
            title = episode.title,
            durationMs = episode.durationMs,
            streamLocator = "xtream://episode/" + episode.providerEpisodeId.trim(),
            extension = episode.containerExtension,
            available = true,
            lastSeenGeneration = generation,
        )
    }
}
