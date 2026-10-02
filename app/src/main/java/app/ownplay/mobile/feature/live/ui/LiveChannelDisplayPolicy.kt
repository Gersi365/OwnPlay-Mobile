package app.ownplay.mobile.feature.live.ui

import app.ownplay.mobile.design.ProviderCategoryDisplayPolicy
import app.ownplay.mobile.feature.live.domain.LiveOrganizationChannel

object LiveChannelDisplayPolicy {
    fun displayName(
        channel: LiveOrganizationChannel,
        preferTvgName: Boolean,
        hideChannelPrefix: Boolean = false,
        showCountryFlag: Boolean = false,
    ): String {
        val localName = channel.localName?.trim()?.takeIf(String::isNotEmpty)
        val tvgName = channel.tvgName?.trim()?.takeIf(String::isNotEmpty)
        val providerName = channel.name.trim()
        val base = localName ?: if (preferTvgName) tvgName ?: providerName else providerName
        return ProviderCategoryDisplayPolicy.label(
            rawName = base,
            hideRegionPrefix = hideChannelPrefix,
            showFlag = showCountryFlag,
        )
    }
}
