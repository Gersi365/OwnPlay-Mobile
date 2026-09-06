package app.ownplay.player.series

import app.ownplay.player.persistence.series.EpisodeProgressRow
import app.ownplay.player.persistence.series.ProviderSeriesEntity
import app.ownplay.player.persistence.series.ProviderSeriesSeasonEntity

internal fun buildStoredSeriesDetails(
    seriesEntity: ProviderSeriesEntity,
    seasons: List<ProviderSeriesSeasonEntity>,
    episodeRows: List<EpisodeProgressRow>,
): SeriesDetails? {
    if (seasons.isEmpty()) return null
    val providerSeriesId = seriesEntity.providerSeriesId.toIntOrNull() ?: return null
    val episodesBySeason = episodeRows
        .map { row ->
            SeriesEpisode(
                episodeId = row.episodeId,
                seriesId = row.seriesId,
                seriesTitle = row.seriesTitle,
                providerEpisodeId = row.providerEpisodeId.toIntOrNull() ?: -1,
                seasonNumber = row.seasonNumber,
                episodeNumber = row.episodeNumber,
                title = row.title,
                containerExtension = row.containerExtension,
                durationSeconds = row.durationSeconds,
                posterUrl = row.posterRef,
                positionMs = row.positionMs,
                durationMs = row.durationMs,
                progressCompleted = row.progressCompleted == true,
                progressUpdatedAtEpochMillis = row.progressUpdatedAtEpochMillis,
            )
        }
        .groupBy(SeriesEpisode::seasonNumber)
    val summary = SeriesSummary(
        seriesId = seriesEntity.seriesId,
        providerSeriesId = providerSeriesId,
        categoryKey = seriesEntity.providerCategoryKey,
        name = seriesEntity.providerName,
        posterUrl = seriesEntity.posterRef,
        description = seriesEntity.description,
        rating = seriesEntity.providerRating,
        lastModifiedEpochSeconds = seriesEntity.lastModifiedEpochSeconds,
        isFavorite = false,
    )
    return SeriesDetails(
        series = summary,
        description = seriesEntity.description,
        posterUrl = seriesEntity.posterRef,
        backdropUrls = emptyList(),
        releaseDate = null,
        genre = null,
        country = null,
        director = null,
        cast = null,
        rating = seriesEntity.providerRating,
        seasons = seasons.map { season ->
            SeriesSeason(
                seasonId = season.seasonId,
                seasonNumber = season.seasonNumber,
                name = season.name,
                airDate = season.airDate,
                posterUrl = season.posterRef,
                episodes = episodesBySeason[season.seasonNumber].orEmpty(),
            )
        },
    )
}
