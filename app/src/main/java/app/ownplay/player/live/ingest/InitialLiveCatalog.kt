package app.ownplay.player.live.ingest

import app.ownplay.player.persistence.reconcile.ProviderIdentity
import app.ownplay.player.source.m3u.M3uEntry
import app.ownplay.player.source.m3u.M3uPlaylist
import app.ownplay.player.source.xtream.XtreamCategory
import app.ownplay.player.source.xtream.XtreamLiveStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class IncomingLiveCategory(
    val providerKey: String,
    val name: String,
    val parentProviderKey: String?,
    val providerOrder: Long,
)

data class IncomingLiveChannel(
    val providerKey: String,
    val providerStreamId: String?,
    val providerCategoryKey: String?,
    val providerName: String,
    val tvgId: String?,
    val tvgName: String?,
    val locatorValue: String,
    val logoValue: String?,
    val providerOrder: Long,
) {
    override fun toString(): String =
        "IncomingLiveChannel(providerKey=$providerKey, providerStreamId=$providerStreamId, " +
            "providerCategoryKey=$providerCategoryKey, providerName=$providerName, " +
            "tvgId=$tvgId, tvgName=$tvgName, locatorValue=<redacted>, " +
            "logoValue=${if (logoValue == null) "null" else "<redacted>"}, " +
            "providerOrder=$providerOrder)"
}

data class IncomingLiveCatalog(
    val categories: List<IncomingLiveCategory>,
    val channels: List<IncomingLiveChannel>,
)

object InitialLiveCatalogFactory {
    fun fromXtream(
        categories: List<XtreamCategory>,
        streams: List<XtreamLiveStream>,
    ): IncomingLiveCatalog = IncomingLiveCatalog(
        categories = categories.mapIndexed { index, category ->
            IncomingLiveCategory(
                providerKey = category.id,
                name = category.name,
                parentProviderKey = category.parentId?.takeIf(String::isNotBlank),
                providerOrder = index.toLong(),
            )
        },
        channels = streams.mapIndexedNotNull { index, stream ->
            if (stream.streamId <= 0) return@mapIndexedNotNull null
            IncomingLiveChannel(
                providerKey = ProviderIdentity.xtreamLiveStream(stream.streamId),
                providerStreamId = stream.streamId.toString(),
                providerCategoryKey = stream.categoryId?.takeIf(String::isNotBlank),
                providerName = stream.name,
                tvgId = stream.epgChannelId,
                tvgName = null,
                locatorValue = stream.directSource
                    ?.takeIf(String::isNotBlank)
                    ?.let(PlaybackLocatorDescriptor::directUrl)
                    ?: PlaybackLocatorDescriptor.xtreamLive(stream.streamId),
                logoValue = stream.iconUrl?.takeIf(String::isNotBlank),
                providerOrder = index.toLong(),
            )
        },
    )

    fun fromM3u(playlist: M3uPlaylist): IncomingLiveCatalog {
        val categories = linkedMapOf<String, IncomingLiveCategory>()
        val baseProviderKeys = playlist.entries.map(ProviderIdentity::m3u)
        val providerKeyCounts = baseProviderKeys.groupingBy { key -> key }.eachCount()
        val emittedProviderKeys = hashSetOf<String>()
        val channels = playlist.entries.mapIndexedNotNull { index, entry ->
            val categoryName = entry.groupTitle?.trim()?.takeIf(String::isNotEmpty)
            val categoryKey = categoryName?.let(::m3uCategoryKey)
            if (categoryName != null && categoryKey != null && categoryKey !in categories) {
                categories[categoryKey] = IncomingLiveCategory(
                    providerKey = categoryKey,
                    name = categoryName,
                    parentProviderKey = null,
                    providerOrder = categories.size.toLong(),
                )
            }

            val baseProviderKey = baseProviderKeys[index]
            val providerKey = if ((providerKeyCounts[baseProviderKey] ?: 0) > 1) {
                "$baseProviderKey:${m3uCollisionKey(entry)}"
            } else {
                baseProviderKey
            }
            // Exact duplicate rows add no playable value and would otherwise still collide.
            if (!emittedProviderKeys.add(providerKey)) return@mapIndexedNotNull null

            IncomingLiveChannel(
                providerKey = providerKey,
                providerStreamId = null,
                providerCategoryKey = categoryKey,
                providerName = entry.displayName,
                tvgId = entry.tvgId,
                tvgName = entry.tvgName,
                locatorValue = PlaybackLocatorDescriptor.directUrl(entry.streamUrl),
                logoValue = entry.logoUrl?.takeIf(String::isNotBlank),
                providerOrder = index.toLong(),
            )
        }

        return IncomingLiveCatalog(
            categories = categories.values.toList(),
            channels = channels,
        )
    }

    private fun m3uCategoryKey(name: String): String {
        val normalized = normalizeM3uIdentity(name)
        return "m3u:group:${sha256(normalized)}"
    }

    private fun m3uCollisionKey(entry: M3uEntry): String {
        val uri = runCatching { URI(entry.streamUrl.trim()) }.getOrNull()
        val locatorSignature = if (uri == null) {
            entry.streamUrl.trim()
        } else {
            buildString {
                append(uri.scheme?.lowercase(Locale.ROOT).orEmpty())
                append("://")
                append(uri.host?.lowercase(Locale.ROOT).orEmpty())
                if (uri.port >= 0) {
                    append(':')
                    append(uri.port)
                }
                append(uri.path.orEmpty())
            }
        }
        return sha256(
            listOf(
                normalizeM3uIdentity(entry.displayName),
                normalizeM3uIdentity(entry.groupTitle.orEmpty()),
                locatorSignature,
            ).joinToString(separator = "|"),
        )
    }

    private fun normalizeM3uIdentity(value: String): String =
        value.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

object PlaybackLocatorDescriptor {
    private const val PREFIX = "ownplay-locator-v1"

    fun directUrl(url: String): String = "$PREFIX|direct|$url"

    fun xtreamLive(streamId: Int): String {
        require(streamId > 0) { "Xtream stream ID must be positive" }
        return "$PREFIX|xtream-live|$streamId"
    }
}
