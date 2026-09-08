package app.ownplay.player.ui

import app.ownplay.player.live.LiveCategory
import app.ownplay.player.live.LiveChannelItem
import app.ownplay.player.testing.normalizedSource
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOrganizationPresentationTest {
    @Test
    fun `category hierarchy separates visible and hidden while preserving order`() {
        val sections = liveOrganizationCategorySections(
            listOf(
                category("news", "News", hidden = false),
                category("sports", "Sports", hidden = true),
                category("movies", "Movies", hidden = false),
                category("kids", "Kids", hidden = true),
            ),
        )

        assertEquals(listOf("news", "movies"), sections.visible.map { it.providerCategoryKey })
        assertEquals(listOf("sports", "kids"), sections.hidden.map { it.providerCategoryKey })
    }

    @Test
    fun `category detail separates visible and hidden channels`() {
        val channels = listOf(
            channel("one", "news", hidden = false),
            channel("two", "sports", hidden = false),
            channel("three", "news", hidden = true),
            channel("four", "news", hidden = false),
        )

        val sections = liveOrganizationChannelSections(channels, "news")

        assertEquals(listOf("one", "four"), sections.visible.map { it.channelId })
        assertEquals(listOf("three"), sections.hidden.map { it.channelId })
    }

    @Test
    fun `category visibility does not masquerade as direct channel hiding`() {
        val channels = listOf(
            channel(
                id = "inherited",
                categoryKey = "hidden-category",
                hidden = true,
                channelHidden = false,
            ),
            channel(
                id = "direct",
                categoryKey = "hidden-category",
                hidden = true,
                channelHidden = true,
            ),
        )

        val sections = liveOrganizationChannelSections(channels, "hidden-category")

        assertEquals(listOf("inherited"), sections.visible.map { it.channelId })
        assertEquals(listOf("direct"), sections.hidden.map { it.channelId })
    }

    @Test
    fun `custom group member count uses channel membership`() {
        val channels = listOf(
            channel("one", "news", groups = setOf("favorites")),
            channel("two", "news", groups = setOf("favorites", "family")),
            channel("three", "news", groups = setOf("family")),
        )

        assertEquals(2, liveCustomGroupMemberCount(channels, "favorites"))
        assertEquals(2, liveCustomGroupMemberCount(channels, "family"))
        assertEquals(0, liveCustomGroupMemberCount(channels, "missing"))
    }

    @Test
    fun `active Settings path uses hierarchical organization instead of browse edit mode`() {
        val settings = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/ui/SettingsScreen.kt"),
        )
        val organization = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/ui/LiveOrganizationScreen.kt"),
        )

        assertTrue(settings.contains("LiveOrganizationScreen("))
        assertFalse(settings.contains("LiveManagementScreen("))
        assertTrue(organization.contains("Visible Categories"))
        assertTrue(organization.contains("Hidden Categories"))
        assertTrue(organization.contains("Visible Channels"))
        assertTrue(organization.contains("Hidden Channels"))
        assertTrue(organization.contains("Custom Groups"))
        assertFalse(organization.contains("LiveBrowseScreen("))
    }

    @Test
    fun `category reorder receives visible categories and appends hidden keys deterministically`() {
        val organization = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/ui/LiveOrganizationScreen.kt"),
        )

        assertTrue(organization.contains("categories = categorySections.visible"))
        assertTrue(
            organization.contains(
                "orderedCategoryKeys = visibleOrderedKeys + hiddenKeys",
            ),
        )
    }

    private fun category(
        key: String,
        name: String,
        hidden: Boolean,
    ) = LiveCategory(
        providerCategoryKey = key,
        name = name,
        providerOrder = 0L,
        isHidden = hidden,
    )

    private fun channel(
        id: String,
        categoryKey: String,
        hidden: Boolean = false,
        channelHidden: Boolean = hidden,
        groups: Set<String> = emptySet(),
    ) = LiveChannelItem(
        channelId = id,
        sourceId = "source",
        categoryKey = categoryKey,
        categoryName = categoryKey,
        providerName = id,
        localDisplayName = null,
        displayName = id,
        logoRef = null,
        hasLogoOverride = false,
        providerOrder = 0L,
        manualOrder = null,
        favoriteOrder = null,
        isFavorite = false,
        isHidden = hidden,
        availability = "ACTIVE",
        recentAtEpochMillis = null,
        customGroupIds = groups,
        isChannelHidden = channelHidden,
    )
}
