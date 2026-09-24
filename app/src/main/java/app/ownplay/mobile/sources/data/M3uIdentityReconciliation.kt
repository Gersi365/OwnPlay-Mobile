package app.ownplay.mobile.sources.data

import app.ownplay.mobile.data.db.LiveChannelEntity
import app.ownplay.mobile.data.db.ProviderCategoryEntity
import java.net.URI

object M3uIdentityReconciliation {
    fun stableLocatorIdentity(locator: String): String {
        val trimmed = locator.trim()
        if (trimmed.isEmpty()) return trimmed

        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase()
                ?: return normalizeFallback(trimmed)
            val host = uri.host?.lowercase()
                ?: return normalizeFallback(trimmed)
            val port = when {
                scheme == "http" && uri.port == 80 -> -1
                scheme == "https" && uri.port == 443 -> -1
                else -> uri.port
            }
            val query = normalizeQuery(uri.rawQuery)

            buildString {
                append(scheme)
                append("://")
                append(host)
                if (port >= 0) {
                    append(':')
                    append(port)
                }
                append(uri.rawPath.orEmpty())
                if (!query.isNullOrBlank()) {
                    append('?')
                    append(query)
                }
            }
        } catch (_: Exception) {
            normalizeFallback(trimmed)
        }
    }

    fun reconcileExistingChannelIds(
        incoming: List<ProviderLiveChannelRecord>,
        existing: List<LiveChannelEntity>,
        existingCategories: List<ProviderCategoryEntity>,
    ): Map<String, String> {
        if (incoming.isEmpty() || existing.isEmpty()) return emptyMap()

        val liveCategoryProviderKeyByLocalKey = existingCategories
            .asSequence()
            .filter { it.kind == DefaultSourceCatalogLoader.KIND_LIVE }
            .associate { it.categoryKey to it.providerKey }

        val existingByProviderKey = uniqueIndex(existing) { row ->
            row.providerKey.trim().takeIf(String::isNotBlank)
        }
        val incomingByTvgId = uniqueIndex(incoming) { row -> normalizeTvgId(row.tvgId) }
        val existingByTvgId = uniqueIndex(existing) { row -> normalizeTvgId(row.tvgId) }
        val incomingByLocator = uniqueIndex(incoming) { row ->
            stableLocatorIdentity(row.streamLocator).takeIf(String::isNotBlank)
        }
        val existingByLocator = uniqueIndex(existing) { row ->
            stableLocatorIdentity(row.streamLocator).takeIf(String::isNotBlank)
        }
        val incomingByMetadata = uniqueIndex(incoming) { row ->
            metadataKey(row.name, row.categoryProviderKey)
        }
        val existingByMetadata = uniqueIndex(existing) { row ->
            metadataKey(
                name = row.name,
                group = row.categoryKey?.let(liveCategoryProviderKeyByLocalKey::get),
            )
        }

        val claimedExistingIds = mutableSetOf<String>()
        val reconciled = linkedMapOf<String, String>()

        incoming.forEach { row ->
            val tvgKey = normalizeTvgId(row.tvgId)
            val locatorKey = stableLocatorIdentity(row.streamLocator).takeIf(String::isNotBlank)
            val metadataIdentity = metadataKey(row.name, row.categoryProviderKey)
            val candidates = buildList {
                existingByProviderKey[row.providerKey]?.let(::add)
                if (
                    tvgKey != null &&
                    incomingByTvgId[tvgKey]?.proposedChannelId == row.proposedChannelId
                ) {
                    existingByTvgId[tvgKey]?.let(::add)
                }
                if (
                    locatorKey != null &&
                    incomingByLocator[locatorKey]?.proposedChannelId == row.proposedChannelId
                ) {
                    existingByLocator[locatorKey]?.let(::add)
                }
                if (
                    incomingByMetadata[metadataIdentity]?.proposedChannelId ==
                    row.proposedChannelId
                ) {
                    existingByMetadata[metadataIdentity]?.let(::add)
                }
            }

            val match = candidates.firstOrNull { candidate ->
                candidate.channelId !in claimedExistingIds
            }
            if (match != null) {
                claimedExistingIds += match.channelId
                reconciled[row.proposedChannelId] = match.channelId
            }
        }

        return reconciled
    }

    private fun normalizeFallback(locator: String): String {
        val withoutFragment = locator.substringBefore('#')
        val queryIndex = withoutFragment.indexOf('?')
        if (queryIndex < 0) return withoutFragment

        val base = withoutFragment.substring(0, queryIndex)
        val query = normalizeQuery(withoutFragment.substring(queryIndex + 1))
        return if (query.isNullOrBlank()) base else "$base?$query"
    }

    private fun normalizeQuery(rawQuery: String?): String? {
        if (rawQuery.isNullOrBlank()) return null

        return rawQuery
            .split('&')
            .asSequence()
            .filter(String::isNotBlank)
            .filterNot { segment ->
                val rawKey = segment.substringBefore('=').trim().lowercase()
                rawKey in TRANSIENT_AUTH_QUERY_KEYS
            }
            .sorted()
            .joinToString("&")
            .takeIf(String::isNotBlank)
    }

    private fun normalizeTvgId(value: String?): String? =
        value
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotBlank)

    private fun metadataKey(
        name: String,
        group: String?,
    ): String = "${normalizeText(name)}|${normalizeText(group.orEmpty())}"

    private fun normalizeText(value: String): String =
        value
            .trim()
            .lowercase()
            .replace(WHITESPACE, " ")

    private fun <T> uniqueIndex(
        rows: List<T>,
        key: (T) -> String?,
    ): Map<String, T> {
        val grouped = linkedMapOf<String, MutableList<T>>()
        rows.forEach { row ->
            key(row)?.let { identity ->
                grouped.getOrPut(identity) { mutableListOf() } += row
            }
        }
        return grouped
            .filterValues { values -> values.size == 1 }
            .mapValues { (_, values) -> values.single() }
    }

    private val WHITESPACE = Regex("\\s+")

    private val TRANSIENT_AUTH_QUERY_KEYS = setOf(
        "access_token",
        "apikey",
        "api_key",
        "auth",
        "auth_token",
        "bearer",
        "credential",
        "credentials",
        "exp",
        "expires",
        "expiry",
        "jwt",
        "pass",
        "password",
        "session",
        "session_id",
        "sid",
        "sig",
        "signature",
        "token",
        "user",
        "username",
    )
}
