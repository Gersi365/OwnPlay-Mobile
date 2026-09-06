package app.ownplay.player.source.xtream

import android.util.Xml
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
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser

data class XtreamXmlTvProgram(
    val channelId: String,
    val title: String,
    val description: String?,
    val startEpochSeconds: Long?,
    val endEpochSeconds: Long?,
)

data class XtreamXmlTvSnapshot(
    val programsByChannelId: Map<String, List<XtreamXmlTvProgram>>,
) {
    val matchedChannelCount: Int get() = programsByChannelId.size
    val programCount: Int get() = programsByChannelId.values.sumOf(List<XtreamXmlTvProgram>::size)
}

class XtreamXmlTvClient(
    private val httpClient: OkHttpClient = SourceHttpClient.shared,
) {
    suspend fun load(
        serverUrl: String,
        credentials: XtreamCredentials,
        channelIds: Set<String>,
        allowCleartext: Boolean,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
    ): SourceResult<XtreamXmlTvSnapshot> {
        if (channelIds.isEmpty()) {
            return SourceResult.Success(XtreamXmlTvSnapshot(emptyMap()))
        }
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
            .addPathSegment("xmltv.php")
            .addQueryParameter("username", credentials.username)
            .addQueryParameter("password", credentials.password)
            .build()
        val request = Request.Builder().url(url).get().build()

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
                            val body = response.body
                            if (body == null) {
                                SourceResult.Failure(SourceError.MalformedResponse)
                            } else {
                                val snapshot = try {
                                    body.byteStream().use { input ->
                                        parseXmlTv(
                                            input = input,
                                            channelIds = channelIds,
                                            nowEpochSeconds = nowEpochSeconds,
                                        )
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    return@withContext SourceResult.Failure(
                                        SourceError.MalformedResponse,
                                    )
                                }
                                SourceResult.Success(snapshot)
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
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

    private fun parseXmlTv(
        input: java.io.InputStream,
        channelIds: Set<String>,
        nowEpochSeconds: Long,
    ): XtreamXmlTvSnapshot {
        val earliest = nowEpochSeconds - HISTORY_WINDOW_SECONDS
        val latest = nowEpochSeconds + FUTURE_WINDOW_SECONDS
        val programs = linkedMapOf<String, MutableList<XtreamXmlTvProgram>>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val channelId = parser.getAttributeValue(null, "channel")?.trim()
                val start = parseXmlTvTime(parser.getAttributeValue(null, "start"))
                val stop = parseXmlTvTime(parser.getAttributeValue(null, "stop"))
                if (
                    channelId != null &&
                    channelId in channelIds &&
                    XmlTvProgramWindow.overlaps(
                        startEpochSeconds = start,
                        stopEpochSeconds = stop,
                        earliestEpochSeconds = earliest,
                        latestEpochSeconds = latest,
                    )
                ) {
                    var title: String? = null
                    var description: String? = null
                    val programmeDepth = parser.depth
                    while (parser.next().also { event = it } != XmlPullParser.END_DOCUMENT) {
                        if (
                            event == XmlPullParser.END_TAG &&
                            parser.name == "programme" &&
                            parser.depth == programmeDepth
                        ) {
                            break
                        }
                        if (event == XmlPullParser.START_TAG) {
                            when (parser.name) {
                                "title" -> title = parser.nextText().trim().takeIf(String::isNotBlank)
                                "desc" -> description = parser.nextText().trim().takeIf(String::isNotBlank)
                            }
                        }
                    }
                    val safeTitle = title ?: "Program"
                    programs.getOrPut(channelId) { mutableListOf() }.add(
                        XtreamXmlTvProgram(
                            channelId = channelId,
                            title = safeTitle,
                            description = description,
                            startEpochSeconds = start,
                            endEpochSeconds = stop,
                        ),
                    )
                }
            }
            event = parser.next()
        }

        val normalized = programs.mapValues { (_, entries) ->
            entries.sortedWith(
                compareBy<XtreamXmlTvProgram> { it.startEpochSeconds ?: Long.MAX_VALUE }
                    .thenBy(XtreamXmlTvProgram::title),
            )
        }
        return XtreamXmlTvSnapshot(normalized)
    }

    private fun parseXmlTvTime(raw: String?): Long? {
        val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
        val candidates = listOf(
            value,
            value.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2"),
        )
        val formatters = listOf(
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss XXX"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmm XXX"),
        )
        for (candidate in candidates) {
            for (formatter in formatters) {
                try {
                    return OffsetDateTime.parse(candidate, formatter).toEpochSecond()
                } catch (_: DateTimeParseException) {
                    // Try the next supported XMLTV form.
                }
            }
        }
        return null
    }

    private companion object {
        const val HISTORY_WINDOW_SECONDS = 6L * 60L * 60L
        const val FUTURE_WINDOW_SECONDS = 48L * 60L * 60L
    }
}
