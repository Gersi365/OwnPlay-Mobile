package app.ownplay.mobile.sources.data.m3u

import app.ownplay.mobile.sources.data.ProviderTransport
import java.io.StringReader
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

data class M3uXmltvProgram(
    val channelId: String,
    val title: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
)

class M3uXmltvGuide internal constructor(
    private val programsByChannel: Map<String, List<M3uXmltvProgram>>,
) {
    fun programsFor(channelId: String?): List<M3uXmltvProgram> {
        val normalized = channelId?.trim()?.takeIf(String::isNotBlank) ?: return emptyList()
        return programsByChannel[normalized].orEmpty()
    }
}

interface M3uXmltvClient {
    suspend fun fetch(remoteUrl: String): M3uXmltvGuide

    suspend fun fetchPrograms(remoteUrl: String, channelId: String): List<M3uXmltvProgram> =
        fetch(remoteUrl).programsFor(channelId)
}

class OkHttpM3uXmltvClient(
    private val transport: ProviderTransport,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : M3uXmltvClient {
    private data class CacheEntry(val loadedAtMs: Long, val programs: List<M3uXmltvProgram>)

    private val cache = object : LinkedHashMap<String, CacheEntry>(CACHE_MAX_CHANNELS, 0.75f, true) {}

    override suspend fun fetch(remoteUrl: String): M3uXmltvGuide {
        val normalized = remoteUrl.trim()
        require(normalized.isNotEmpty()) { "XMLTV URL must not be blank" }
        val response = fetchResponse(normalized)
        return withContext(Dispatchers.Default) { M3uXmltvParser.parse(response) }
    }

    override suspend fun fetchPrograms(
        remoteUrl: String,
        channelId: String,
    ): List<M3uXmltvProgram> {
        val normalizedUrl = remoteUrl.trim().takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("XMLTV URL must not be blank")
        val normalizedChannel = channelId.trim().takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("XMLTV channel id must not be blank")
        val key = cacheKey(normalizedUrl, normalizedChannel)
        val now = nowMillis()
        synchronized(cache) {
            cache[key]
                ?.takeIf { now - it.loadedAtMs < CACHE_TTL_MS }
                ?.let { return it.programs }
        }

        val response = fetchResponse(normalizedUrl)
        val programs = withContext(Dispatchers.Default) {
            M3uXmltvParser.programsFor(response, normalizedChannel)
        }
        synchronized(cache) {
            cache[key] = CacheEntry(nowMillis(), programs)
            while (cache.size > CACHE_MAX_CHANNELS) {
                val oldest = cache.keys.iterator().next()
                cache.remove(oldest)
            }
        }
        return programs
    }

    private suspend fun fetchResponse(url: String): String {
        val response = try {
            transport.get(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("XMLTV provider request failed")
        }
        return response.body
    }

    private fun cacheKey(url: String, channelId: String): String {
        val value = "$url\u0000$channelId".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private companion object {
        const val CACHE_TTL_MS = 5 * 60 * 1_000L
        const val CACHE_MAX_CHANNELS = 8
    }
}

internal class M3uXmltvParseException(cause: Throwable? = null) :
    IllegalArgumentException("XMLTV response is malformed", cause)

internal object M3uXmltvParser {
    private const val MAX_PROGRAMS = 100_000
    private const val MAX_PROGRAMS_PER_CHANNEL = 10_000
    private const val MAX_TITLE_LENGTH = 4_096
    private val timestampRegex = Regex("""^(\d{14}|\d{12}|\d{10}|\d{8})(?:\s*([+-]\d{4}|Z))?.*$""")
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun parse(content: String): M3uXmltvGuide {
        return parseInternal(content, targetChannelId = null, maxPrograms = MAX_PROGRAMS)
    }

    fun programsFor(content: String, channelId: String): List<M3uXmltvProgram> =
        parseInternal(
            content = content,
            targetChannelId = channelId,
            maxPrograms = MAX_PROGRAMS_PER_CHANNEL,
        ).programsFor(channelId)

    private fun parseInternal(
        content: String,
        targetChannelId: String?,
        maxPrograms: Int,
    ): M3uXmltvGuide {
        if (content.isBlank()) throw M3uXmltvParseException()
        if (content.contains("<!DOCTYPE", ignoreCase = true) || content.contains("<!ENTITY", ignoreCase = true)) {
            throw M3uXmltvParseException()
        }

        val programs = ArrayList<M3uXmltvProgram>()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        val handler = object : DefaultHandler() {
            var current: PendingProgram? = null
            var titleBuffer: StringBuilder? = null

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                when ((qName ?: localName).orEmpty().lowercase()) {
                    "programme" -> {
                        if (programs.size >= maxPrograms) {
                            current = null
                            return
                        }
                        val channelId = attributes.getValue("channel")?.trim().orEmpty()
                        if (targetChannelId != null && channelId != targetChannelId) {
                            current = null
                            return
                        }
                        val start = parseTimestamp(attributes.getValue("start"))
                        val end = parseTimestamp(attributes.getValue("stop"))
                        current = if (channelId.isNotBlank() && start != null && end != null && end > start) {
                            PendingProgram(channelId, start, end)
                        } else {
                            null
                        }
                    }
                    "title" -> if (current != null) titleBuffer = StringBuilder()
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                val buffer = titleBuffer ?: return
                if (buffer.length >= MAX_TITLE_LENGTH) return
                buffer.append(ch, start, minOf(length, MAX_TITLE_LENGTH - buffer.length))
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when ((qName ?: localName).orEmpty().lowercase()) {
                    "title" -> {
                        current?.title = titleBuffer?.toString()?.trim()?.takeIf(String::isNotBlank)
                        titleBuffer = null
                    }
                    "programme" -> {
                        val pending = current
                        if (pending != null && pending.title != null && programs.size < maxPrograms) {
                            programs += M3uXmltvProgram(
                                channelId = pending.channelId,
                                title = pending.title!!,
                                startEpochSeconds = pending.startEpochSeconds,
                                endEpochSeconds = pending.endEpochSeconds,
                            )
                        }
                        current = null
                        titleBuffer = null
                    }
                }
            }
        }

        try {
            val reader = factory.newSAXParser().xmlReader
            reader.entityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.contentHandler = handler
            reader.parse(InputSource(StringReader(content)))
        } catch (error: Exception) {
            throw M3uXmltvParseException(error)
        }

        val byChannel = programs
            .distinctBy { program ->
                listOf(
                    program.channelId,
                    program.startEpochSeconds.toString(),
                    program.endEpochSeconds.toString(),
                    program.title,
                ).joinToString("\u0000")
            }
            .groupBy(M3uXmltvProgram::channelId)
            .mapValues { (_, rows) ->
                rows.sortedWith(
                    compareBy<M3uXmltvProgram>(M3uXmltvProgram::startEpochSeconds)
                        .thenBy(M3uXmltvProgram::endEpochSeconds)
                        .thenBy(M3uXmltvProgram::title),
                )
            }
        return M3uXmltvGuide(byChannel)
    }

    private fun parseTimestamp(raw: String?): Long? {
        val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
        val match = timestampRegex.matchEntire(value) ?: return null
        val digits = match.groupValues[1].padEnd(14, '0')
        val offsetText = match.groupValues[2]
        val offset = when {
            offsetText.isBlank() || offsetText == "Z" -> ZoneOffset.UTC
            else -> runCatching {
                val sign = if (offsetText[0] == '-') -1 else 1
                val hours = offsetText.substring(1, 3).toInt()
                val minutes = offsetText.substring(3, 5).toInt()
                ZoneOffset.ofTotalSeconds(sign * (hours * 3_600 + minutes * 60))
            }.getOrNull() ?: return null
        }
        return runCatching {
            LocalDateTime.parse(digits, timestampFormatter).toEpochSecond(offset)
        }.getOrNull()
    }

    private data class PendingProgram(
        val channelId: String,
        val startEpochSeconds: Long,
        val endEpochSeconds: Long,
        var title: String? = null,
    )
}
