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
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
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

class XtreamClient(
    private val httpClient: OkHttpClient = SourceHttpClient.shared,
    private val json: Json = Json { isLenient = true },
    allowCleartext: Boolean = false,
) {
    private val defaultAllowCleartext = allowCleartext

    suspend fun validateAccount(
        serverUrl: String,
        credentials: XtreamCredentials,
        allowCleartext: Boolean = defaultAllowCleartext,
    ): SourceResult<XtreamAccountInfo> {
        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            return SourceResult.Failure(SourceError.InvalidCredentials)
        }

        val response = requestJson(
            serverUrl = serverUrl,
            credentials = credentials,
            action = null,
            allowCleartext = allowCleartext,
        )
        if (response is SourceResult.Failure) return response
        return parseAccount((response as SourceResult.Success).value)
    }

    suspend fun getLiveCategories(
        serverUrl: String,
        credentials: XtreamCredentials,
        allowCleartext: Boolean = defaultAllowCleartext,
    ): SourceResult<List<XtreamCategory>> = getCategories(
        serverUrl = serverUrl,
        credentials = credentials,
        action = "get_live_categories",
        allowCleartext = allowCleartext,
    )

    suspend fun getLiveStreams(
        serverUrl: String,
        credentials: XtreamCredentials,
        categoryId: String? = null,
        allowCleartext: Boolean = defaultAllowCleartext,
    ): SourceResult<List<XtreamLiveStream>> {
        val response = requestJson(
            serverUrl = serverUrl,
            credentials = credentials,
            action = "get_live_streams",
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
                val streamId = item.int("stream_id") ?: return@mapNotNull null
                val name = item.text("name") ?: return@mapNotNull null
                XtreamLiveStream(
                    streamId = streamId,
                    name = name,
                    categoryId = item.text("category_id")?.takeIf(String::isNotBlank),
                    iconUrl = item.text("stream_icon")?.takeIf(String::isNotBlank),
                    epgChannelId = item.text("epg_channel_id")?.takeIf(String::isNotBlank),
                    archiveDurationDays = item.int("tv_archive_duration"),
                    directSource = item.text("direct_source")?.takeIf(String::isNotBlank),
                )
            },
        )
    }

    suspend fun getVodCategories(
        serverUrl: String,
        credentials: XtreamCredentials,
        allowCleartext: Boolean = defaultAllowCleartext,
    ): SourceResult<List<XtreamCategory>> = getCategories(
        serverUrl = serverUrl,
        credentials = credentials,
        action = "get_vod_categories",
        allowCleartext = allowCleartext,
    )

    suspend fun getVodStreams(
        serverUrl: String,
        credentials: XtreamCredentials,
        categoryId: String? = null,
        allowCleartext: Boolean = defaultAllowCleartext,
    ): SourceResult<List<XtreamVodStream>> {
        val response = requestJson(
            serverUrl = serverUrl,
            credentials = credentials,
            action = "get_vod_streams",
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
                val streamId = item.int("stream_id")?.takeIf { it > 0 } ?: return@mapNotNull null
                val name = item.text("name") ?: return@mapNotNull null
                XtreamVodStream(
                    streamId = streamId,
                    name = name,
                    categoryId = item.text("category_id")?.takeIf(String::isNotBlank),
                    posterUrl = item.text("stream_icon")?.takeIf(String::isNotBlank),
                    containerExtension = item.text("container_extension")?.takeIf(String::isNotBlank),
                    rating = item.double("rating") ?: item.double("rating_5based"),
                    addedAtEpochSeconds = item.long("added")?.takeIf { it >= 0L },
                    directSource = item.text("direct_source")?.takeIf(String::isNotBlank),
                )
            },
        )
    }

    suspend fun getVodInfo(
        serverUrl: String,
        credentials: XtreamCredentials,
        vodId: Int,
        allowCleartext: Boolean = defaultAllowCleartext,
    ): SourceResult<XtreamVodInfo> {
        if (vodId <= 0) return SourceResult.Failure(SourceError.MalformedResponse)
        val response = requestJson(
            serverUrl = serverUrl,
            credentials = credentials,
            action = "get_vod_info",
            extraQuery = mapOf("vod_id" to vodId.toString()),
            allowCleartext = allowCleartext,
        )
        if (response is SourceResult.Failure) return response

        val root = (response as SourceResult.Success).value as? JsonObject
            ?: return SourceResult.Failure(SourceError.MalformedResponse)
        val info = root["info"] as? JsonObject
        val movieData = root["movie_data"] as? JsonObject
        if (info == null && movieData == null) {
            return SourceResult.Failure(SourceError.MalformedResponse)
        }

        return SourceResult.Success(
            XtreamVodInfo(
                streamId = movieData?.int("stream_id") ?: info?.int("stream_id") ?: vodId,
                name = info?.text("name") ?: movieData?.text("name"),
                originalName = info?.text("o_name"),
                description = info?.text("description") ?: info?.text("plot"),
                posterUrl = info?.text("cover_big")
                    ?: info?.text("movie_image")
                    ?: movieData?.text("stream_icon"),
                backdropUrls = info?.stringList("backdrop_path").orEmpty(),
                releaseDate = info?.text("releasedate") ?: info?.text("release_date"),
                durationSeconds = info?.long("duration_secs")?.takeIf { it > 0L },
                durationLabel = info?.text("duration") ?: info?.text("episode_run_time"),
                genre = info?.text("genre"),
                country = info?.text("country"),
                director = info?.text("director"),
                cast = info?.text("actors") ?: info?.text("cast"),
                rating = info?.double("rating") ?: movieData?.double("rating"),
                youtubeTrailer = info?.text("youtube_trailer"),
                containerExtension = movieData?.text("container_extension")
                    ?: info?.text("container_extension"),
                categoryId = movieData?.text("category_id")?.takeIf(String::isNotBlank)
                    ?: info?.text("category_id")?.takeIf(String::isNotBlank),
                directSource = movieData?.text("direct_source")?.takeIf(String::isNotBlank)
                    ?: info?.text("direct_source")?.takeIf(String::isNotBlank),
            ),
        )
    }

    suspend fun getShortEpg(
        serverUrl: String,
        credentials: XtreamCredentials,
        streamId: String,
        limit: Int = 12,
        allowCleartext: Boolean = defaultAllowCleartext,
    ): SourceResult<XtreamEpgGuide> {
        val normalizedStreamId = streamId.trim()
        if (normalizedStreamId.toIntOrNull()?.let { it > 0 } != true) {
            return SourceResult.Failure(SourceError.MalformedResponse)
        }
        val response = requestJson(
            serverUrl = serverUrl,
            credentials = credentials,
            action = "get_short_epg",
            extraQuery = mapOf(
                "stream_id" to normalizedStreamId,
                "limit" to limit.coerceIn(2, 50).toString(),
            ),
            allowCleartext = allowCleartext,
        )
        if (response is SourceResult.Failure) return response

        val root = (response as SourceResult.Success).value as? JsonObject
            ?: return SourceResult.Failure(SourceError.MalformedResponse)
        val listings = root["epg_listings"] as? JsonArray
            ?: return SourceResult.Success(XtreamEpgGuide(emptyList()))

        val programs = listings.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val rawTitle = item.text("title") ?: return@mapNotNull null
            XtreamEpgProgram(
                id = item.text("id"),
                title = decodeMaybeBase64(rawTitle),
                description = item.text("description")
                    ?.takeIf(String::isNotBlank)
                    ?.let(::decodeMaybeBase64),
                startEpochSeconds = item.long("start_timestamp"),
                endEpochSeconds = item.long("stop_timestamp")
                    ?: item.long("end_timestamp"),
                startLabel = item.text("start"),
                endLabel = item.text("end"),
            )
        }
        return SourceResult.Success(XtreamEpgGuide(programs))
    }

    private suspend fun getCategories(
        serverUrl: String,
        credentials: XtreamCredentials,
        action: String,
        allowCleartext: Boolean,
    ): SourceResult<List<XtreamCategory>> {
        val response = requestJson(
            serverUrl = serverUrl,
            credentials = credentials,
            action = action,
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

    private suspend fun requestJson(
        serverUrl: String,
        credentials: XtreamCredentials,
        action: String?,
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
        val urlBuilder = baseUrl.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", credentials.username)
            .addQueryParameter("password", credentials.password)
        action?.let { urlBuilder.addQueryParameter("action", it) }
        extraQuery.forEach { (key, value) ->
            urlBuilder.addQueryParameter(key, value)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build()

        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 || response.code == 403 -> {
                            SourceResult.Failure(SourceError.AuthenticationFailed)
                        }

                        response.code == 408 || response.code == 504 -> {
                            SourceResult.Failure(SourceError.Timeout)
                        }

                        !response.isSuccessful -> {
                            SourceResult.Failure(SourceError.HttpFailure(response.code))
                        }

                        else -> {
                            val body = response.body.string()
                            val element = runCatching { json.parseToJsonElement(body) }
                                .getOrElse {
                                    return@withContext SourceResult.Failure(
                                        SourceError.MalformedResponse,
                                    )
                                }
                            SourceResult.Success(element)
                        }
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

    private fun parseAccount(element: JsonElement): SourceResult<XtreamAccountInfo> {
        val root = element as? JsonObject
            ?: return SourceResult.Failure(SourceError.MalformedResponse)
        val userInfo = root["user_info"] as? JsonObject
            ?: return SourceResult.Failure(SourceError.MalformedResponse)
        val authenticated = userInfo.flag("auth")
            ?: return SourceResult.Failure(SourceError.MalformedResponse)
        if (!authenticated) {
            return SourceResult.Failure(SourceError.AuthenticationFailed)
        }

        val server = (root["server_info"] as? JsonObject)?.let { serverInfo ->
            XtreamServerInfo(
                protocol = serverInfo.text("server_protocol"),
                timezone = serverInfo.text("timezone"),
            )
        }
        val formats = (userInfo["allowed_output_formats"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

        return SourceResult.Success(
            XtreamAccountInfo(
                status = userInfo.text("status"),
                expiresAtEpochSeconds = userInfo.long("exp_date"),
                maxConnections = userInfo.int("max_connections"),
                isTrial = userInfo.flag("is_trial"),
                allowedOutputFormats = formats,
                serverInfo = server,
            ),
        )
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

    private fun JsonObject.flag(key: String): Boolean? = when (text(key)?.lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> null
    }

    private fun decodeMaybeBase64(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length < 4 || trimmed.any(Char::isWhitespace)) return trimmed
        val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
        val decoded = runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
            ?: return trimmed
        val candidate = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded))
                .toString()
                .trim()
        }.getOrNull() ?: return trimmed
        if (candidate.isEmpty()) return trimmed
        val printable = candidate.all { char ->
            char == '\n' || char == '\r' || char == '\t' || !char.isISOControl()
        }
        return if (printable) candidate else trimmed
    }
}
