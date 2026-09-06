package app.ownplay.player.source.m3u

import android.util.Xml
import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceResult
import app.ownplay.player.source.SourceValidator
import app.ownplay.player.source.UrlValidationResult
import app.ownplay.player.source.network.SourceHttpClient
import app.ownplay.player.source.xtream.XmlTvProgramWindow
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser

data class M3uXmlTvProgram(
    val channelId: String,
    val title: String,
    val description: String?,
    val startEpochSeconds: Long?,
    val endEpochSeconds: Long?,
)

data class M3uXmlTvSnapshot(
    val programsByChannelId: Map<String, List<M3uXmlTvProgram>>,
)

class M3uXmlTvClient(
    private val httpClient: OkHttpClient = SourceHttpClient.shared,
) {
    suspend fun load(
        epgUrls: List<String>,
        channelIds: Set<String>,
        allowCleartext: Boolean,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
    ): SourceResult<M3uXmlTvSnapshot> {
        if (epgUrls.isEmpty() || channelIds.isEmpty()) {
            return SourceResult.Success(M3uXmlTvSnapshot(emptyMap()))
        }

        val merged = linkedMapOf<String, MutableList<M3uXmlTvProgram>>()
        var successfulSourceCount = 0
        var lastFailure: SourceError? = null

        for (rawUrl in epgUrls.asSequence().map(String::trim).filter(String::isNotEmpty).distinct()) {
            when (
                val loaded = loadSingle(
                    xmlTvUrl = rawUrl,
                    channelIds = channelIds,
                    allowCleartext = allowCleartext,
                    nowEpochSeconds = nowEpochSeconds,
                )
            ) {
                is SourceResult.Success -> {
                    successfulSourceCount += 1
                    loaded.value.programsByChannelId.forEach { (channelId, programs) ->
                        merged.getOrPut(channelId) { mutableListOf() }.addAll(programs)
                    }
                }
                is SourceResult.Failure -> lastFailure = loaded.error
            }
        }

        if (successfulSourceCount == 0) {
            return SourceResult.Failure(lastFailure ?: SourceError.MalformedResponse)
        }

        val normalized = merged.mapValues { (_, programs) ->
            programs.distinctBy { program ->
                listOf(
                    program.channelId,
                    program.startEpochSeconds,
                    program.endEpochSeconds,
                    program.title,
                )
            }.sortedWith(
                compareBy<M3uXmlTvProgram> { it.startEpochSeconds ?: Long.MAX_VALUE }
                    .thenBy(M3uXmlTvProgram::title),
            )
        }
        return SourceResult.Success(M3uXmlTvSnapshot(normalized))
    }

    private suspend fun loadSingle(
        xmlTvUrl: String,
        channelIds: Set<String>,
        allowCleartext: Boolean,
        nowEpochSeconds: Long,
    ): SourceResult<M3uXmlTvSnapshot> {
        val validation = SourceValidator.validateRemotePlaylistUrl(xmlTvUrl)
        if (validation is UrlValidationResult.Invalid) {
            return SourceResult.Failure(validation.error)
        }
        val valid = validation as UrlValidationResult.Valid
        if (valid.usesCleartext && !allowCleartext) {
            return SourceResult.Failure(SourceError.CleartextTransportRequiresOptIn)
        }
        val url = valid.normalizedUrl.toHttpUrlOrNull()
            ?: return SourceResult.Failure(SourceError.InvalidUrl)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/xml, text/xml, application/gzip, */*")
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
                            val body = response.body
                            if (body == null) {
                                SourceResult.Failure(SourceError.MalformedResponse)
                            } else {
                                val snapshot = try {
                                    openXmlTvStream(body.byteStream()).use { input ->
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

    private fun openXmlTvStream(input: InputStream): InputStream {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        return if (first == GZIP_MAGIC_FIRST && second == GZIP_MAGIC_SECOND) {
            GZIPInputStream(buffered)
        } else {
            buffered
        }
    }

    private fun parseXmlTv(
        input: InputStream,
        channelIds: Set<String>,
        nowEpochSeconds: Long,
    ): M3uXmlTvSnapshot {
        val earliest = nowEpochSeconds - HISTORY_WINDOW_SECONDS
        val latest = nowEpochSeconds + FUTURE_WINDOW_SECONDS
        val programs = linkedMapOf<String, MutableList<M3uXmlTvProgram>>()
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
                    programs.getOrPut(channelId) { mutableListOf() }.add(
                        M3uXmlTvProgram(
                            channelId = channelId,
                            title = title ?: "Program",
                            description = description,
                            startEpochSeconds = start,
                            endEpochSeconds = stop,
                        ),
                    )
                }
            }
            event = parser.next()
        }

        return M3uXmlTvSnapshot(
            programsByChannelId = programs.mapValues { (_, entries) ->
                entries.sortedWith(
                    compareBy<M3uXmlTvProgram> { it.startEpochSeconds ?: Long.MAX_VALUE }
                        .thenBy(M3uXmlTvProgram::title),
                )
            },
        )
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
        const val GZIP_MAGIC_FIRST = 0x1f
        const val GZIP_MAGIC_SECOND = 0x8b
    }
}
