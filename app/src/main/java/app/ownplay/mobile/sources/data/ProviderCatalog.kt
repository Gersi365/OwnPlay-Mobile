package app.ownplay.mobile.sources.data

import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import app.ownplay.mobile.sources.domain.SourceType

enum class CatalogSection {
    LIVE_CATEGORIES,
    LIVE_CHANNELS,
    MOVIE_CATEGORIES,
    MOVIES,
    SERIES_CATEGORIES,
    SERIES,
}

data class ProviderCatalogSnapshot(
    val sourceType: SourceType,
    val categories: List<ProviderCategoryRecord>,
    val liveChannels: List<ProviderLiveChannelRecord>,
    val movies: List<ProviderMovieRecord>,
    val series: List<ProviderSeriesRecord>,
    val authoritativeSections: Set<CatalogSection> = CatalogSection.entries.toSet(),
)

data class ProviderCategoryRecord(
    val kind: String,
    val providerKey: String,
    val name: String,
    val providerOrder: Int,
)

data class ProviderLiveChannelRecord(
    val proposedChannelId: String,
    val providerKey: String,
    val providerStreamId: String?,
    val categoryProviderKey: String?,
    val name: String,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val streamLocator: String,
    val providerOrder: Int,
) {
    override fun toString(): String =
        "ProviderLiveChannelRecord(proposedChannelId=$proposedChannelId, providerKey=<redacted>, " +
            "providerStreamId=$providerStreamId, categoryProviderKey=$categoryProviderKey, " +
            "name=$name, tvgId=$tvgId, tvgName=$tvgName, logoUrl=<redacted>, " +
            "streamLocator=<redacted>, providerOrder=$providerOrder)"
}

data class ProviderMovieRecord(
    val proposedMovieId: String,
    val providerStreamId: String,
    val categoryProviderKey: String?,
    val name: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val extension: String?,
    val rating: String?,
    val providerOrder: Int,
) {
    override fun toString(): String =
        "ProviderMovieRecord(proposedMovieId=$proposedMovieId, providerStreamId=$providerStreamId, " +
            "categoryProviderKey=$categoryProviderKey, name=$name, posterUrl=<redacted>, " +
            "backdropUrl=<redacted>, extension=$extension, rating=$rating, providerOrder=$providerOrder)"
}

data class ProviderSeriesRecord(
    val proposedSeriesId: String,
    val providerSeriesId: String,
    val categoryProviderKey: String?,
    val name: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val description: String?,
    val rating: String?,
    val providerOrder: Int,
) {
    override fun toString(): String =
        "ProviderSeriesRecord(proposedSeriesId=$proposedSeriesId, providerSeriesId=$providerSeriesId, " +
            "categoryProviderKey=$categoryProviderKey, name=$name, posterUrl=<redacted>, " +
            "backdropUrl=<redacted>, description=$description, rating=$rating, providerOrder=$providerOrder)"
}

interface SourceCatalogLoader {
    suspend fun load(
        sourceId: SourceId,
        sourceType: SourceType,
        baseLocator: String,
        secret: app.ownplay.mobile.data.security.SourceSecret,
    ): ProviderCatalogSnapshot
}

class CatalogLoadException(
    val category: SourceRefreshFailureCategory,
    cause: Throwable? = null,
) : Exception(category.name, cause)
