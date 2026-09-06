package app.ownplay.player.source.xtream

import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceResult
import app.ownplay.player.source.SourceValidator
import app.ownplay.player.source.UrlValidationResult
import app.ownplay.player.source.credential.XtreamCredentials
import app.ownplay.player.source.network.SourceHttpClient
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class XtreamSeriesClient(
    private val httpClient: OkHttpClient = SourceHttpClient.shared,
    private val json: Json = Json { isLenient = true },
    allowCleartext: Boolean = false,
) {
    private val defaultAllowCleartext = allowCleartext

    suspend fun getSeriesCategories(
        serverUrl: String,
        credentials: XtreamCredentials,
        allowCleartext: Boolean = defaultAllowCleartext,
    ): SourceResult<List<XtreamCategory>> {
        val response = requestJson(
            serverUrl = serverUrl,
            credentials = credentials,
            action = "get_series_categories",
            allowCleartext = allowCleartext,
        )
        if (response is SourceResult.Failure) return response
        val array = (response as SourceResult.Success).value as? JsonArray
            ?: return SourceResult.Failure(SourceError.MalformedResponse)
        return SourceResult.Success(
            array.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val id = item.text("category_id")?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val name = item.text("category_name") ?: return@mapNotNull null
                XtreamCategory(
                    id = id,
                    name = name,
                    parentId = item.text("parent_id")?.takeIf(String::isNotBlank),
                )
            },
        )
    }

    suspend fun getSeries(
        serverUrl: String,
        credentials: XtreamCredentials,
        categoryId: String? = null,
        allowCleartext: Boolean = defaultAllowCleartext,
    ): SourceResult<List<XtreamSeriesSummary>> {
        val response = requestJson(
            serverUrl = serverUrl,
            credentials = credentials,
            action = "get_series",
            extraQuery = buildMap {
                categoryId?.takeIf(String::isNotBlank)?.let { put("category_id", it) }
            },
            allowCleartext = allowCleartext,
        )
        if (response is SourceResult.Failure) return response
        val array = (response as SourceResult.Success).value as? JsonArray
            ?: return SourceResult.Failure(SourceError.MalformedResponse)
        return SourceResult.Success(
            array.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val seriesId = item.int("series_id")?.takeIf { it > 0 } ?: return@mapNotNull null
                val name = item.text("name") ?: return@mapNotNull null
                XtreamSeriesSummary(
                    seriesId = seriesId,
                    name = name,
                    categoryId = item.text("category_id")?.takeIf(String::isNotBlank),
                    posterUrl = item.text("cover")?.takeIf(String::isNotBlank),
                    rating = item.double("rating") ?: item.double("rating_5based"),
                    lastModifiedEpochSeconds = item.long("last_modified")?.takeIf { it >= 0L },
                    description = item.text("plot") ?: item.text("description"),
                )
            },
        )
    }

    suspend fun getSeriesInfo(
        serverUrl: String,
        credentials: XtreamCredentials,
        seriesId: Int,
        allowCleartext: Boolean = defaultAllowCleartext,
    ): SourceResult<XtreamSeriesInfo> {
        if (seriesId <= 0) return SourceResult.Failure(SourceError.MalformedResponse)
        val response = requestJson(
            serverUrl = serverUrl,
            credentials = credentials,
            action = "get_series_info",
            extraQuery = mapOf("series_id" to seriesId.toString()),
            allowCleartext = allowCleartext,
        )
        if (response is SourceResult.Failure) return response
        val root = (response as SourceResult.Success).value as? JsonObject
            ?: return SourceResult.Failure(SourceError.MalformedResponse)
        val info = root["info"] as? JsonObject
        val seasons = (root["seasons"] as? JsonArray)
            ?.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val number = (item.int("season_number") ?: item.int("season"))
                    ?.takeIf { it >= 0 }
                    ?: return@mapNotNull null
                XtreamSeriesSeason(
                    seasonNumber = number,
                    name = item.text("name"),
                    airDate = item.text("air_date"),
                    posterUrl = item.text("cover")?.takeIf(String::isNotBlank),
                )
            }
            .orEmpty()
        val episodes = mutableListOf<XtreamSeriesEpisode>()
        (root["episodes"] as? JsonObject)?.forEach { (seasonKey, value) ->
            val seasonFromKey = seasonKey.toIntOrNull()?.takeIf { it >= 0 }
            (value as? JsonArray)?.forEach episodeLoop@{ element ->
                val item = element as? JsonObject ?: return@episodeLoop
                val episodeId = item.int("id")?.takeIf { it > 0 } ?: return@episodeLoop
                val episodeNumber = (item.int("episode_num") ?: item.int("episode"))
                    ?.takeIf { it > 0 }
                    ?: return@episodeLoop
                val seasonNumber = item.int("season")
                    ?.takeIf { it >= 0 }
                    ?: seasonFromKey
                    ?: return@episodeLoop
                val episodeInfo = item["info"] as? JsonObject
                val title = item.text("title")
                    ?: episodeInfo?.text("name")
                    ?: "Episode $episodeNumber"
                episodes += XtreamSeriesEpisode(
                    episodeId = episodeId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    title = title,
                    containerExtension = item.text("container_extension")?.takeIf(String::isNotBlank),
                    durationSeconds = episodeInfo?.long("duration_secs")?.takeIf { it > 0L },
                    description = episodeInfo?.text("plot") ?: episodeInfo?.text("description"),
                    posterUrl = episodeInfo?.text("movie_image")?.takeIf(String::isNotBlank),
                    rating = episodeInfo?.double("rating"),
                    addedAtEpochSeconds = item.long("added")?.takeIf { it >= 0L },
                )
            }
        }
        if (info == null && seasons.isEmpty() && episodes.isEmpty()) {
            return SourceResult.Failure(SourceError.MalformedResponse)
        }
        return SourceResult.Success(
            XtreamSeriesInfo(
                seriesId = seriesId,
                name = info?.text("name"),
                description = info?.text("plot") ?: info?.text("description"),
                posterUrl = info?.text("cover")?.takeIf(String::isNotBlank),
                backdropUrls = info?.stringList("backdrop_path").orEmpty(),
                releaseDate = info?.text("releaseDate") ?: info?.text("release_date"),
                genre = info?.text("genre"),
                country = info?.text("country"),
                director = info?.text("director"),
                cast = info?.text("cast") ?: info?.text("actors"),
                rating = info?.double("rating") ?: info?.double("rating_5based"),
                seasons = seasons.sortedBy(XtreamSeriesSeason::seasonNumber),
                episodes = episodes.sortedWith(
                    compareBy(XtreamSeriesEpisode::seasonNumber, XtreamSeriesEpisode::episodeNumber),
                ),
            ),
        )
    }

    private suspend fun requestJson(
        serverUrl: String,
        credentials: XtreamCredentials,
        action: String,
        extraQuery: Map<String, String> = emptyMap(),
        allowCleartext: Boolean,
    ): SourceResult<JsonElement> {
        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            return SourceResult.Failure(SourceError.InvalidCredentials)
        }
        val validation = SourceValidator.validateXtreamServer(serverUrl)
        if (validation is UrlValidationResult.Invalid) {
            return SourceResult.Failure(validation.error)
        }
        val valid = validation as UrlValidationResult.Valid
        if (valid.usesCleartext && !allowCleartext) {
            return SourceResult.Failure(SourceError.CleartextTransportRequiresOptIn)
        }
        val baseUrl = valid.normalizedUrl.toHttpUrlOrNull()
            ?: return SourceResult.Failure(SourceError.InvalidUrl)
        val url = baseUrl.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", credentials.username)
            .addQueryParameter("password", credentials.password)
            .addQueryParameter("action", action)
            .apply {
                extraQuery.forEach { (key, value) -> addQueryParameter(key, value) }
            }
            .build()
        val request = Request.Builder().url(url).get().build()
        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 || response.code == 403 ->
                            SourceResult.Failure(SourceError.AuthenticationFailed)
                        response.code == 408 || response.code == 504 ->
                            SourceResult.Failure(SourceError.Timeout)
                        !response.isSuccessful ->
                            SourceResult.Failure(SourceError.HttpFailure(response.code))
                        else -> runCatching {
                            json.parseToJsonElement(response.body.string())
                        }.fold(
                            onSuccess = { SourceResult.Success(it) },
                            onFailure = { SourceResult.Failure(SourceError.MalformedResponse) },
                        )
                    }
                }
            } catch (_: SocketTimeoutException) {
                SourceResult.Failure(SourceError.Timeout)
            } catch (_: SSLException) {
                SourceResult.Failure(SourceError.SecureConnectionFailed)
            } catch (_: UnknownHostException) {
                SourceResult.Failure(SourceError.NetworkUnavailable)
            } catch (_: ConnectException) {
                SourceResult.Failure(SourceError.NetworkUnavailable)
            } catch (_: NoRouteToHostException) {
                SourceResult.Failure(SourceError.NetworkUnavailable)
            } catch (_: IOException) {
                SourceResult.Failure(SourceError.NetworkUnavailable)
            } catch (_: IllegalArgumentException) {
                SourceResult.Failure(SourceError.InvalidUrl)
            }
        }
    }

    private fun JsonObject.text(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)
            ?.contentOrNull
            ?.takeUnless { it.equals("null", ignoreCase = true) }
    }

    private fun JsonObject.int(key: String): Int? = text(key)?.trim()?.toIntOrNull()
    private fun JsonObject.long(key: String): Long? = text(key)?.trim()?.toLongOrNull()
    private fun JsonObject.double(key: String): Double? =
        text(key)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
    private fun JsonObject.stringList(key: String): List<String> = when (val element = this[key]) {
        is JsonArray -> element.mapNotNull { item ->
            (item as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        }
        is JsonPrimitive -> element.contentOrNull?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
        else -> emptyList()
    }
}
