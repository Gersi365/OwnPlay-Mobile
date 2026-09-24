package app.ownplay.mobile.feature.live.ui

import app.ownplay.mobile.feature.live.domain.LiveOrganizationChannel
import java.util.Locale

object LiveChannelDisplayPolicy {
    fun displayName(
        channel: LiveOrganizationChannel,
        preferTvgName: Boolean,
        hideChannelPrefix: Boolean = false,
    ): String {
        val localName = channel.localName?.trim()?.takeIf(String::isNotEmpty)
        val tvgName = channel.tvgName?.trim()?.takeIf(String::isNotEmpty)
        val providerName = channel.name.trim()
        val base = localName ?: if (preferTvgName) tvgName ?: providerName else providerName
        return ChannelNamePrefixPolicy.displayName(base, hideChannelPrefix)
    }
}

internal object ChannelNamePrefixPolicy {
    private val knownPrefixes: Set<String> by lazy {
        buildSet {
            Locale.getISOCountries().forEach { alpha2 ->
                val locale = Locale.Builder().setRegion(alpha2).build()
                add(normalize(alpha2))
                runCatching { locale.isO3Country }
                    .getOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?.let { add(normalize(it)) }
                locale.getDisplayCountry(Locale.ENGLISH)
                    .takeIf(String::isNotBlank)
                    ?.let { add(normalize(it)) }
            }
            addAll(
                listOf(
                    "UK",
                    "UAE",
                    "KOSOVO",
                    "XK",
                    "XKX",
                    "NORTH MACEDONIA",
                    "MACEDONIA",
                    "CZECHIA",
                    "CZECH REPUBLIC",
                ).map(::normalize),
            )
        }
    }

    fun displayName(rawName: String, hidePrefix: Boolean): String {
        val trimmed = rawName.trim()
        if (!hidePrefix) return trimmed

        val separatorIndex = listOf(
            trimmed.indexOf('|'),
            trimmed.indexOf(':'),
        ).filter { it > 0 }.minOrNull() ?: return trimmed

        val prefix = trimmed.substring(0, separatorIndex).trim()
        val remainder = trimmed.substring(separatorIndex + 1).trimStart()
        if (prefix.isEmpty() || remainder.isEmpty()) return trimmed

        return if (normalize(prefix) in knownPrefixes) remainder else trimmed
    }

    private fun normalize(value: String): String =
        value.trim().uppercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
