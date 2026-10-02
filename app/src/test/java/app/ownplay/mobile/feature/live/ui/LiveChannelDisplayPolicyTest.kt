package app.ownplay.mobile.feature.live.ui

import app.ownplay.mobile.feature.live.domain.LiveOrganizationChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveChannelDisplayPolicyTest {
    private val channel = LiveOrganizationChannel(
        channelId = "channel-1",
        name = "Provider News",
        tvgName = "Guide News",
        providerCategoryId = "news",
        providerOrder = 0,
    )

    @Test
    fun providerNameIsDefaultAndTvgNameIsUsedOnlyWhenPreferred() {
        assertEquals(
            "Provider News",
            LiveChannelDisplayPolicy.displayName(channel, preferTvgName = false),
        )
        assertEquals(
            "Guide News",
            LiveChannelDisplayPolicy.displayName(channel, preferTvgName = true),
        )
    }

    @Test
    fun missingTvgNameFallsBackToProviderNameWhenPreferred() {
        assertEquals(
            "Provider News",
            LiveChannelDisplayPolicy.displayName(
                channel.copy(tvgName = "   "),
                preferTvgName = true,
            ),
        )
    }

    @Test
    fun localNameRemainsAuthoritativeForDisplay() {
        assertEquals(
            "My News",
            LiveChannelDisplayPolicy.displayName(
                channel.copy(localName = " My News "),
                preferTvgName = true,
            ),
        )
    }

    @Test
    fun prefixCleanupIsConservativeAndNeverBlindlyStripsSeparators() {
        val knownCountryPrefix = channel.copy(name = "AL |   Top Channel", tvgName = null)
        assertEquals(
            "AL |   Top Channel",
            LiveChannelDisplayPolicy.displayName(
                knownCountryPrefix,
                preferTvgName = false,
                hideChannelPrefix = false,
            ),
        )
        assertEquals(
            "Top Channel",
            LiveChannelDisplayPolicy.displayName(
                knownCountryPrefix,
                preferTvgName = false,
                hideChannelPrefix = true,
            ),
        )

        val unknownPrefix = channel.copy(name = "HBO | Movies", tvgName = null)
        assertEquals(
            "HBO | Movies",
            LiveChannelDisplayPolicy.displayName(
                unknownPrefix,
                preferTvgName = false,
                hideChannelPrefix = true,
            ),
        )
        val unknownColonPrefix = channel.copy(name = "Sports: Main", tvgName = null)
        assertEquals(
            "Sports: Main",
            LiveChannelDisplayPolicy.displayName(
                unknownColonPrefix,
                preferTvgName = false,
                hideChannelPrefix = true,
            ),
        )
    }

    @Test
    fun countryFlagUsesTheSharedProviderLabelPolicy() {
        val prefixed = channel.copy(name = "EUROPE | ALBANIA | Top Channel", tvgName = null)
        assertEquals(
            "🇦🇱 Top Channel",
            LiveChannelDisplayPolicy.displayName(
                prefixed,
                preferTvgName = false,
                hideChannelPrefix = false,
                showCountryFlag = true,
            ),
        )
    }
}
