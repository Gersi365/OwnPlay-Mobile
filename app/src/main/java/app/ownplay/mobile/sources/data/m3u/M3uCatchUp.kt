package app.ownplay.mobile.sources.data.m3u

import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.sources.domain.ConnectionValidation
import app.ownplay.mobile.sources.domain.SourceConnectionSecurityPolicy
import java.util.concurrent.ConcurrentHashMap

internal class M3uCatchUpResolver(
    private val m3uClient: M3uClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class CacheEntry(val loadedAtMs: Long, val result: M3uParseResult)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun resolveEntry(
        secret: SourceSecret.M3uRemote,
        tvgId: String?,
        streamLocator: String,
    ): M3uEntry? {
        val playlistUrl = secret.playlistUrl.trim()
        if (playlistUrl.isEmpty()) return null
        val now = nowMillis()
        val parsed = cache[playlistUrl]
            ?.takeIf { now - it.loadedAtMs < CACHE_TTL_MS }
            ?.result
            ?: m3uClient.fetch(playlistUrl).also { result ->
                cache[playlistUrl] = CacheEntry(now, result)
            }

        val normalizedTvgId = tvgId?.trim()?.takeIf(String::isNotBlank)
        if (normalizedTvgId != null) {
            val matches = parsed.entries.filter { it.tvgId?.trim() == normalizedTvgId }
            if (matches.size == 1) return matches.single()
        }
        val normalizedStream = streamLocator.trim()
        return parsed.entries.firstOrNull { it.streamUrl.trim() == normalizedStream }
    }

    fun supports(entry: M3uEntry): Boolean = M3uCatchUpTemplate.supports(entry)

    fun playbackUrl(
        entry: M3uEntry,
        startEpochSeconds: Long,
        endEpochSeconds: Long,
    ): String? = M3uCatchUpTemplate.render(entry, startEpochSeconds, endEpochSeconds)

    private companion object {
        const val CACHE_TTL_MS = 5 * 60 * 1_000L
    }
}

internal object M3uCatchUpTemplate {
    private val disabledModes = setOf("0", "false", "off", "none", "disabled")

    fun supports(entry: M3uEntry): Boolean {
        if (entry.catchUpMode?.trim()?.lowercase() in disabledModes) return false
        return !entry.catchUpSource.isNullOrBlank()
    }

    fun render(
        entry: M3uEntry,
        startEpochSeconds: Long,
        endEpochSeconds: Long,
    ): String? {
        if (!supports(entry) || startEpochSeconds <= 0L || endEpochSeconds <= startEpochSeconds) {
            return null
        }
        val template = entry.catchUpSource?.trim() ?: return null
        val duration = endEpochSeconds - startEpochSeconds
        var rendered = template
        val replacements = linkedMapOf(
            "{utc}" to startEpochSeconds.toString(),
            "{start}" to startEpochSeconds.toString(),
            "${'$'}{start}" to startEpochSeconds.toString(),
            "{utcend}" to endEpochSeconds.toString(),
            "{end}" to endEpochSeconds.toString(),
            "${'$'}{end}" to endEpochSeconds.toString(),
            "{duration}" to duration.toString(),
            "${'$'}{duration}" to duration.toString(),
        )
        var replaced = false
        replacements.forEach { (token, value) ->
            if (rendered.contains(token)) {
                rendered = rendered.replace(token, value)
                replaced = true
            }
        }
        if (!replaced) return null
        return when (val validation = SourceConnectionSecurityPolicy.normalizeRemoteMediaUrl(rendered)) {
            is ConnectionValidation.Valid -> validation.normalizedUrl
            is ConnectionValidation.Invalid -> null
        }
    }
}
