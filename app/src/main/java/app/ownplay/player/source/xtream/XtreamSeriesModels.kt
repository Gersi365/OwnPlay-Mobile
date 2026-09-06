package app.ownplay.player.source.xtream

data class XtreamSeriesSummary(
    val seriesId: Int,
    val name: String,
    val categoryId: String?,
    val posterUrl: String?,
    val rating: Double?,
    val lastModifiedEpochSeconds: Long?,
    val description: String?,
)

data class XtreamSeriesSeason(
    val seasonNumber: Int,
    val name: String?,
    val airDate: String?,
    val posterUrl: String?,
)

data class XtreamSeriesEpisode(
    val episodeId: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val containerExtension: String?,
    val durationSeconds: Long?,
    val description: String?,
    val posterUrl: String?,
    val rating: Double?,
    val addedAtEpochSeconds: Long?,
)

data class XtreamSeriesInfo(
    val seriesId: Int,
    val name: String?,
    val description: String?,
    val posterUrl: String?,
    val backdropUrls: List<String>,
    val releaseDate: String?,
    val genre: String?,
    val country: String?,
    val director: String?,
    val cast: String?,
    val rating: Double?,
    val seasons: List<XtreamSeriesSeason>,
    val episodes: List<XtreamSeriesEpisode>,
)
