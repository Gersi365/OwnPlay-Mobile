package app.ownplay.mobile.sources.data.xtream

import app.ownplay.mobile.sources.data.ProviderPayloadKind
import app.ownplay.mobile.sources.data.ProviderPayloadValidation
import app.ownplay.mobile.sources.data.ProviderPayloadValidationResult
import app.ownplay.mobile.sources.data.ProviderTransport

interface XtreamClient {
    suspend fun accountInfo(connection: XtreamConnection): XtreamAccountInfo =
        XtreamAccountInfo(emptyList())
    suspend fun shortEpg(
        connection: XtreamConnection,
        streamId: String,
        limit: Int = 4,
    ): List<XtreamEpgEntry> = emptyList()
    suspend fun epgTable(
        connection: XtreamConnection,
        streamId: String,
    ): List<XtreamEpgEntry> = emptyList()
    suspend fun liveCategories(connection: XtreamConnection): List<XtreamCategory>
    suspend fun liveStreams(connection: XtreamConnection): List<XtreamLiveStream>
    suspend fun liveStreams(
        connection: XtreamConnection,
        categoryId: String,
    ): List<XtreamLiveStream> = liveStreams(connection)
    suspend fun movieCategories(connection: XtreamConnection): List<XtreamCategory>
    suspend fun movies(connection: XtreamConnection): List<XtreamMovie>
    suspend fun movieInfo(connection: XtreamConnection, movieId: String): XtreamMovieDetail =
        throw UnsupportedOperationException("Movie detail is not supported")
    suspend fun seriesCategories(connection: XtreamConnection): List<XtreamCategory>
    suspend fun series(connection: XtreamConnection): List<XtreamSeries>
    suspend fun seriesInfo(connection: XtreamConnection, seriesId: String): XtreamSeriesDetail
}

class OkHttpXtreamClient(
    private val transport: ProviderTransport,
) : XtreamClient {
    override suspend fun accountInfo(connection: XtreamConnection): XtreamAccountInfo {
        val info = parse(connection, null, XtreamPayloadParser::accountInfo)
        if (info.authenticated == false) {
            throw XtreamClientException(XtreamClientFailureCategory.AUTHENTICATION)
        }
        return info
    }

    override suspend fun shortEpg(
        connection: XtreamConnection,
        streamId: String,
        limit: Int,
    ): List<XtreamEpgEntry> {
        require(streamId.isNotBlank()) { "Stream id must not be blank" }
        return parse(
            connection = connection,
            action = "get_short_epg",
            parser = XtreamPayloadParser::shortEpg,
            extraParameters = mapOf(
                "stream_id" to streamId,
                "limit" to limit.coerceIn(1, 20).toString(),
            ),
        )
    }

    override suspend fun epgTable(
        connection: XtreamConnection,
        streamId: String,
    ): List<XtreamEpgEntry> {
        require(streamId.isNotBlank()) { "Stream id must not be blank" }
        return parse(
            connection = connection,
            action = "get_simple_data_table",
            parser = XtreamPayloadParser::epgListings,
            extraParameters = mapOf("stream_id" to streamId),
        )
    }

    override suspend fun liveCategories(
        connection: XtreamConnection,
    ): List<XtreamCategory> =
        parse(connection, "get_live_categories", XtreamPayloadParser::categories)

    override suspend fun liveStreams(
        connection: XtreamConnection,
    ): List<XtreamLiveStream> =
        parse(connection, "get_live_streams", XtreamPayloadParser::liveStreams)

    override suspend fun liveStreams(
        connection: XtreamConnection,
        categoryId: String,
    ): List<XtreamLiveStream> {
        require(categoryId.isNotBlank()) { "Category id must not be blank" }
        return parse(
            connection = connection,
            action = "get_live_streams",
            parser = XtreamPayloadParser::liveStreams,
            extraParameters = mapOf("category_id" to categoryId.trim()),
        )
    }

    override suspend fun movieCategories(
        connection: XtreamConnection,
    ): List<XtreamCategory> =
        parse(connection, "get_vod_categories", XtreamPayloadParser::categories)

    override suspend fun movies(
        connection: XtreamConnection,
    ): List<XtreamMovie> =
        parse(connection, "get_vod_streams", XtreamPayloadParser::movies)

    override suspend fun movieInfo(
        connection: XtreamConnection,
        movieId: String,
    ): XtreamMovieDetail {
        require(movieId.isNotBlank()) { "Movie id must not be blank" }
        return parse(
            connection = connection,
            action = "get_vod_info",
            parser = XtreamPayloadParser::movieInfo,
            extraParameters = mapOf("vod_id" to movieId),
        )
    }

    override suspend fun seriesCategories(
        connection: XtreamConnection,
    ): List<XtreamCategory> =
        parse(connection, "get_series_categories", XtreamPayloadParser::categories)

    override suspend fun series(
        connection: XtreamConnection,
    ): List<XtreamSeries> =
        parse(connection, "get_series", XtreamPayloadParser::series)

    override suspend fun seriesInfo(
        connection: XtreamConnection,
        seriesId: String,
    ): XtreamSeriesDetail {
        require(seriesId.isNotBlank()) { "Series id must not be blank" }
        return parse(
            connection = connection,
            action = "get_series_info",
            parser = XtreamPayloadParser::seriesInfo,
            extraParameters = mapOf("series_id" to seriesId),
        )
    }

    private suspend fun <T> parse(
        connection: XtreamConnection,
        action: String?,
        parser: (String) -> T,
        extraParameters: Map<String, String> = emptyMap(),
    ): T {
        val response = transport.get(
            XtreamUrlBuilder.playerApi(
                baseUrl = connection.baseUrl,
                username = connection.username,
                password = connection.password,
                action = action,
                extraParameters = extraParameters,
            ),
        )

        if (response.statusCode == 401 || response.statusCode == 403) {
            throw XtreamClientException(XtreamClientFailureCategory.AUTHENTICATION)
        }
        if (response.statusCode !in 200..299) {
            val category = if (response.statusCode == 429 || response.statusCode in 500..599) {
                XtreamClientFailureCategory.TRANSIENT_PROVIDER
            } else {
                XtreamClientFailureCategory.PROVIDER
            }
            throw XtreamClientException(category)
        }

        when (
            ProviderPayloadValidation.validate(
                body = response.body,
                kind = ProviderPayloadKind.JSON,
            )
        ) {
            ProviderPayloadValidationResult.Valid -> Unit
            is ProviderPayloadValidationResult.Invalid -> {
                throw XtreamClientException(XtreamClientFailureCategory.INVALID_PAYLOAD)
            }
        }

        return try {
            parser(response.body)
        } catch (_: Exception) {
            throw XtreamClientException(XtreamClientFailureCategory.INVALID_PAYLOAD)
        }
    }
}

class XtreamClientException(
    val category: XtreamClientFailureCategory,
) : Exception(category.name)

enum class XtreamClientFailureCategory {
    AUTHENTICATION,
    PROVIDER,
    TRANSIENT_PROVIDER,
    INVALID_PAYLOAD,
}
