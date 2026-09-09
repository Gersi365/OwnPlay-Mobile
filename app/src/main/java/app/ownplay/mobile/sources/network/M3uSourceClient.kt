package app.ownplay.mobile.sources.network

import app.ownplay.mobile.sources.CatalogType
import app.ownplay.mobile.sources.ProviderCategory
import app.ownplay.mobile.sources.ProviderItem
import app.ownplay.mobile.sources.SourceDescriptor
import app.ownplay.mobile.sources.SourceSnapshot
import app.ownplay.mobile.sources.XtreamCredentials
import app.ownplay.mobile.sources.stableSourceKey
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class M3uEntry(
    val name: String,
    val streamUrl: String,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val group: String?,
)

internal object M3uParser {
    private val attributeRegex = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    fun parse(text: String): List<M3uEntry> {
        val result = mutableListOf<M3uEntry>()
        var pending: PendingEntry? = null
        var extGroup: String? = null
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attributes = attributeRegex.findAll(line).associate { match ->
                        match.groupValues[1].lowercase() to match.groupValues[2].trim()
                    }
                    val displayName = line.substringAfterLast(',', missingDelimiterValue = "").trim()
                    pending = PendingEntry(
                        name = displayName.ifBlank { attributes["tvg-name"].orEmpty() },
                        tvgId = attributes["tvg-id"].takeUnless { it.isNullOrBlank() },
                        tvgName = attributes["tvg-name"].takeUnless { it.isNullOrBlank() },
                        logoUrl = attributes["tvg-logo"].takeUnless { it.isNullOrBlank() },
                        group = attributes["group-title"].takeUnless { it.isNullOrBlank() },
                    )
                    extGroup = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    extGroup = line.substringAfter(':').trim().ifBlank { null }
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    val metadata = pending
                    if (metadata != null) {
                        val name = metadata.name.ifBlank { metadata.tvgName.orEmpty() }.ifBlank { "Untitled" }
                        result += M3uEntry(
                            name = name,
                            streamUrl = line,
                            tvgId = metadata.tvgId,
                            tvgName = metadata.tvgName,
                            logoUrl = metadata.logoUrl,
                            group = metadata.group ?: extGroup,
                        )
                    }
                    pending = null
                    extGroup = null
                }
            }
        }
        return result
    }

    private data class PendingEntry(
        val name: String,
        val tvgId: String?,
        val tvgName: String?,
        val logoUrl: String?,
        val group: String?,
    )
}

class M3uSourceClient(
    private val httpClient: OkHttpClient,
) : SourceCatalogClient {
    override suspend fun fetch(
        source: SourceDescriptor,
        credentials: XtreamCredentials?,
    ): SourceSnapshot {
        val request = Request.Builder().url(source.locator).get().build()
        val body = httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "M3U request failed with HTTP ${response.code}" }
            response.body.string()
        }
        val entries = M3uParser.parse(body)
        val groups = linkedMapOf<String, Int>()
        entries.forEach { entry ->
            entry.group?.let { group -> groups.putIfAbsent(group, groups.size) }
        }
        val categories = groups.map { (group, order) ->
            ProviderCategory(
                catalogType = CatalogType.LIVE,
                providerKey = group,
                name = group,
                providerOrder = order,
            )
        }
        val items = entries.mapIndexed { index, entry ->
            ProviderItem(
                catalogType = CatalogType.LIVE,
                providerKey = entry.tvgId ?: "url:${stableSourceKey(entry.streamUrl)}",
                categoryKey = entry.group,
                name = entry.name,
                artworkUrl = entry.logoUrl,
                streamLocator = entry.streamUrl,
                containerExtension = null,
                rating = null,
                providerOrder = index,
            )
        }
        return SourceSnapshot(categories = categories, items = items)
    }
}

interface SourceCatalogClient {
    suspend fun fetch(source: SourceDescriptor, credentials: XtreamCredentials?): SourceSnapshot
}
