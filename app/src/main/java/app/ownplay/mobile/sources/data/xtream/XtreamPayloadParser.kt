package app.ownplay.mobile.sources.data.xtream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

object XtreamPayloadParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun accountInfo(body: String): XtreamAccountInfo {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: return XtreamAccountInfo(emptyList())
        val userInfo = root["user_info"] as? JsonObject
        val formats = (userInfo?.get("allowed_output_formats") as? JsonArray)
            ?.mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val serverInfo = root["server_info"] as? JsonObject
        return XtreamAccountInfo(
            allowedOutputFormats = formats,
            authenticated = userInfo?.booleanLike("auth"),
            timezone = serverInfo?.text("timezone"),
        )
    }

    fun shortEpg(body: String): List<XtreamEpgEntry> = epgListings(body)

    fun epgListings(body: String): List<XtreamEpgEntry> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val listings = root["epg_listings"] as? JsonArray ?: return emptyList()
        return listings.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val title = item.text("title")
                ?.let(XtreamEpgTextPolicy::decode)
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            XtreamEpgEntry(
                title = title,
                startEpochSeconds = item.long("start_timestamp"),
                endEpochSeconds = item.long("stop_timestamp"),
            )
        }
    }

    fun categories(body: String): List<XtreamCategory> =
        parseArray(body).mapIndexedNotNull { index, element ->
            val objectValue = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = objectValue.text("category_id") ?: return@mapIndexedNotNull null
            val name = objectValue.text("category_name") ?: return@mapIndexedNotNull null
            XtreamCategory(
                providerCategoryId = id,
                name = name,
                providerOrder = index,
            )
        }

    fun liveStreams(body: String): List<XtreamLiveStream> =
        parseArray(body).mapIndexedNotNull { index, element ->
            val objectValue = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = objectValue.text("stream_id") ?: return@mapIndexedNotNull null
            val name = objectValue.text("name") ?: return@mapIndexedNotNull null
            XtreamLiveStream(
                streamId = id,
                categoryId = objectValue.text("category_id"),
                name = name,
                tvgId = objectValue.text("epg_channel_id") ?: objectValue.text("tvg_id"),
                logoUrl = objectValue.text("stream_icon"),
                containerExtension = objectValue.text("container_extension"),
                providerOrder = index,
                catchUpAvailable = objectValue.booleanLike("tv_archive") == true,
                catchUpDurationDays = objectValue.int("tv_archive_duration")
                    ?.takeIf { it > 0 },
            )
        }

    fun movies(body: String): List<XtreamMovie> =
        parseArray(body).mapIndexedNotNull { index, element ->
            val objectValue = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = objectValue.text("stream_id") ?: return@mapIndexedNotNull null
            val name = objectValue.text("name") ?: return@mapIndexedNotNull null
            XtreamMovie(
                streamId = id,
                categoryId = objectValue.text("category_id"),
                name = name,
                posterUrl = objectValue.text("stream_icon"),
                containerExtension = objectValue.text("container_extension"),
                rating = objectValue.text("rating"),
                providerOrder = index,
            )
        }

    fun movieInfo(body: String): XtreamMovieDetail {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: return XtreamMovieDetail(null, null, null, null, null, null, null, null)
        val info = root["info"] as? JsonObject
        val movieData = root["movie_data"] as? JsonObject
        val releaseDate = info?.text("releasedate") ?: info?.text("release_date")
        val year = info?.text("year") ?: releaseDate
            ?.take(4)
            ?.takeIf { candidate -> candidate.length == 4 && candidate.all(Char::isDigit) }
        val durationSeconds = info?.long("duration_secs")
        val runtimeMs = durationSeconds
            ?.takeIf { it > 0L && it <= Long.MAX_VALUE / 1_000L }
            ?.times(1_000L)
            ?: parseClockDurationMs(info?.text("duration"))

        return XtreamMovieDetail(
            name = info?.text("name") ?: movieData?.text("name"),
            posterUrl = info?.text("movie_image")
                ?: info?.text("cover_big")
                ?: movieData?.text("stream_icon"),
            backdropUrl = info?.firstText("backdrop_path"),
            plot = info?.text("plot") ?: info?.text("description"),
            releaseDate = releaseDate,
            year = year,
            runtimeMs = runtimeMs,
            rating = info?.text("rating") ?: movieData?.text("rating"),
            cast = info?.firstStringList("cast", "actors").orEmpty(),
        )
    }

    fun series(body: String): List<XtreamSeries> =
        parseArray(body).mapIndexedNotNull { index, element ->
            val objectValue = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = objectValue.text("series_id") ?: return@mapIndexedNotNull null
            val name = objectValue.text("name") ?: return@mapIndexedNotNull null
            XtreamSeries(
                seriesId = id,
                categoryId = objectValue.text("category_id"),
                name = name,
                posterUrl = objectValue.text("cover"),
                description = objectValue.text("plot"),
                rating = objectValue.text("rating"),
                providerOrder = index,
            )
        }

    fun seriesInfo(body: String): XtreamSeriesDetail {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: return XtreamSeriesDetail(emptyList())
        val info = root["info"] as? JsonObject
        val episodePayload = root["episodes"] ?: return XtreamSeriesDetail(
            episodes = emptyList(),
            name = info?.text("name"),
            posterUrl = info?.text("cover") ?: info?.text("cover_big"),
            backdropUrl = info?.firstText("backdrop_path"),
            plot = info?.text("plot") ?: info?.text("description"),
            releaseDate = info?.text("releaseDate")
                ?: info?.text("release_date")
                ?: info?.text("releasedate"),
            year = info?.text("year"),
            genre = info?.text("genre"),
            rating = info?.text("rating"),
            cast = info?.firstStringList("cast", "actors").orEmpty(),
        )
        val episodes = when (episodePayload) {
            is JsonObject -> episodePayload.entries.flatMap { (seasonKey, value) ->
                parseSeasonEpisodes(seasonKey, value)
            }
            is JsonArray -> episodePayload.mapNotNull { element ->
                parseEpisode(element as? JsonObject ?: return@mapNotNull null, fallbackSeason = null)
            }
            else -> emptyList()
        }
        val releaseDate = info?.text("releaseDate")
            ?: info?.text("release_date")
            ?: info?.text("releasedate")
        val year = info?.text("year") ?: releaseDate
            ?.take(4)
            ?.takeIf { candidate -> candidate.length == 4 && candidate.all(Char::isDigit) }
        return XtreamSeriesDetail(
            episodes = episodes.distinctBy(XtreamSeriesEpisode::providerEpisodeId),
            name = info?.text("name"),
            posterUrl = info?.text("cover") ?: info?.text("cover_big"),
            backdropUrl = info?.firstText("backdrop_path"),
            plot = info?.text("plot") ?: info?.text("description"),
            releaseDate = releaseDate,
            year = year,
            genre = info?.text("genre"),
            rating = info?.text("rating"),
            cast = info?.firstStringList("cast", "actors").orEmpty(),
        )
    }

    private fun parseSeasonEpisodes(
        seasonKey: String,
        payload: JsonElement,
    ): List<XtreamSeriesEpisode> = when (payload) {
        is JsonArray -> payload.mapNotNull { element ->
            parseEpisode(
                objectValue = element as? JsonObject ?: return@mapNotNull null,
                fallbackSeason = seasonKey.toIntOrNull(),
            )
        }
        is JsonObject -> listOfNotNull(
            parseEpisode(
                objectValue = payload,
                fallbackSeason = seasonKey.toIntOrNull(),
            ),
        )
        else -> emptyList()
    }

    private fun parseEpisode(
        objectValue: JsonObject,
        fallbackSeason: Int?,
    ): XtreamSeriesEpisode? {
        val providerEpisodeId = objectValue.text("id") ?: return null
        val seasonNumber = objectValue.int("season") ?: fallbackSeason ?: return null
        val episodeNumber = objectValue.int("episode_num") ?: return null
        if (seasonNumber < 0 || episodeNumber < 0) return null
        val info = objectValue["info"] as? JsonObject
        val title = objectValue.text("title") ?: info?.text("name") ?: return null
        val durationSeconds = info?.long("duration_secs")
        return XtreamSeriesEpisode(
            providerEpisodeId = providerEpisodeId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            title = title,
            containerExtension = objectValue.text("container_extension"),
            durationMs = durationSeconds
                ?.takeIf { it > 0L && it <= Long.MAX_VALUE / 1_000L }
                ?.times(1_000L),
        )
    }

    private fun parseArray(body: String): JsonArray =
        json.parseToJsonElement(body).jsonArray

    private fun parseClockDurationMs(value: String?): Long? {
        val parts = value
            ?.trim()
            ?.split(':')
            ?.map { it.toLongOrNull() ?: return null }
            ?: return null
        if (parts.size !in 2..3 || parts.any { it < 0L }) return null

        val seconds = try {
            when (parts.size) {
                2 -> {
                    val (minutes, secs) = parts
                    if (secs > 59L) return null
                    Math.addExact(Math.multiplyExact(minutes, 60L), secs)
                }
                3 -> {
                    val (hours, minutes, secs) = parts
                    if (minutes > 59L || secs > 59L) return null
                    Math.addExact(
                        Math.addExact(
                            Math.multiplyExact(hours, 3_600L),
                            Math.multiplyExact(minutes, 60L),
                        ),
                        secs,
                    )
                }
                else -> return null
            }
        } catch (_: ArithmeticException) {
            return null
        }
        return seconds
            .takeIf { it > 0L && it <= Long.MAX_VALUE / 1_000L }
            ?.times(1_000L)
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun JsonObject.firstText(key: String): String? =
        text(key) ?: (this[key] as? JsonArray)
            ?.asSequence()
            ?.mapNotNull { element ->
                (element as? JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            }
            ?.firstOrNull()

    private fun JsonObject.firstStringList(vararg keys: String): List<String> =
        keys.asSequence()
            .map { key -> stringList(key) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

    private fun JsonObject.stringList(key: String): List<String> {
        val values = when (val element = this[key]) {
            is JsonArray -> element.mapNotNull { item ->
                (item as? JsonPrimitive)?.contentOrNull
            }
            is JsonPrimitive -> element.contentOrNull
                ?.split(',', ';', '|')
                .orEmpty()
            else -> emptyList()
        }
        return values.asSequence()
            .map { it.trim() }
            .filter(String::isNotBlank)
            .distinct()
            .toList()
    }

    private fun JsonObject.booleanLike(key: String): Boolean? = when (text(key)?.lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> null
    }

    private fun JsonObject.int(key: String): Int? = text(key)?.toIntOrNull()

    private fun JsonObject.long(key: String): Long? = text(key)?.toLongOrNull()
}
