package app.ownplay.mobile.sources.data

import app.ownplay.mobile.sources.domain.SourceId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object StableIdentity {
    fun providerCategory(
        sourceId: SourceId,
        kind: String,
        providerKey: String,
    ): String = typedId(
        prefix = "category",
        sourceId = sourceId,
        providerType = "provider",
        providerIdentity = "${kind.trim().uppercase()}:${providerKey.trim()}",
    )

    fun xtreamLiveChannel(
        sourceId: SourceId,
        providerStreamId: String,
    ): String = typedId(
        prefix = "live",
        sourceId = sourceId,
        providerType = "xtream",
        providerIdentity = providerStreamId,
    )

    fun xtreamMovie(
        sourceId: SourceId,
        providerStreamId: String,
    ): String = typedId(
        prefix = "movie",
        sourceId = sourceId,
        providerType = "xtream",
        providerIdentity = providerStreamId,
    )

    fun xtreamSeries(
        sourceId: SourceId,
        providerSeriesId: String,
    ): String = typedId(
        prefix = "series",
        sourceId = sourceId,
        providerType = "xtream",
        providerIdentity = providerSeriesId,
    )

    fun xtreamEpisode(
        sourceId: SourceId,
        providerEpisodeId: String,
    ): String = typedId(
        prefix = "episode",
        sourceId = sourceId,
        providerType = "xtream",
        providerIdentity = providerEpisodeId,
    )

    fun m3uLiveChannel(
        sourceId: SourceId,
        tvgId: String?,
        stableLocatorHint: String?,
        normalizedName: String,
        normalizedGroup: String?,
        stableMetadataHint: String? = null,
    ): String {
        val identity = when {
            !tvgId.isNullOrBlank() -> "tvg:${tvgId.trim().lowercase()}"
            !stableLocatorHint.isNullOrBlank() ->
                "locator:${M3uIdentityReconciliation.stableLocatorIdentity(stableLocatorHint)}"
            else -> buildString {
                append("metadata:")
                append(normalizedName.trim().lowercase())
                append('|')
                append(normalizedGroup.orEmpty().trim().lowercase())
                stableMetadataHint
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf(String::isNotBlank)
                    ?.let { metadata ->
                        append('|')
                        append(metadata)
                    }
            }
        }
        return typedId(
            prefix = "live",
            sourceId = sourceId,
            providerType = "m3u",
            providerIdentity = identity,
        )
    }

    private fun typedId(
        prefix: String,
        sourceId: SourceId,
        providerType: String,
        providerIdentity: String,
    ): String {
        require(providerIdentity.isNotBlank()) { "Provider identity must not be blank" }
        val digest = digest(
            sourceId.value,
            providerType,
            prefix,
            providerIdentity.trim(),
        )
        return "$prefix-$digest"
    }

    private fun digest(vararg parts: String): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            messageDigest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            messageDigest.update(':'.code.toByte())
            messageDigest.update(bytes)
            messageDigest.update('|'.code.toByte())
        }
        return messageDigest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
