package app.ownplay.player.live

import app.ownplay.player.persistence.ChannelAvailability
import app.ownplay.player.persistence.live.LiveChannelRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveBrowsePersonalizationFilterTest {
    private val records = listOf(
        record("visible-a", hidden = false, providerOrder = 0),
        record("hidden-a", hidden = true, providerOrder = 1),
        record("visible-b", hidden = false, providerOrder = 2),
    )

    private val memberships = mapOf(
        "visible-a" to setOf("group-a"),
        "hidden-a" to setOf("group-a", "group-b"),
        "visible-b" to setOf("group-b"),
    )

    @Test
    fun hiddenOnlyReturnsOnlyHiddenChannelsWithoutRequiringSeparateIncludeFlag() {
        val result = LiveBrowseProjector.project(
            records = records,
            query = LiveBrowseQuery(hiddenOnly = true),
            customGroupIdsByChannelId = memberships,
        )

        assertEquals(listOf("hidden-a"), result.map { it.channelId })
    }

    @Test
    fun customGroupFilterUsesLocalMembershipOverlay() {
        val result = LiveBrowseProjector.project(
            records = records,
            query = LiveBrowseQuery(customGroupId = "group-b", includeHidden = true),
            customGroupIdsByChannelId = memberships,
        )

        assertEquals(listOf("hidden-a", "visible-b"), result.map { it.channelId })
    }

    @Test
    fun customGroupAndHiddenFiltersComposeDeterministically() {
        val result = LiveBrowseProjector.project(
            records = records,
            query = LiveBrowseQuery(
                customGroupId = "group-a",
                hiddenOnly = true,
                order = LiveBrowseOrder.MY_ORDER,
            ),
            customGroupIdsByChannelId = memberships,
        )

        assertEquals(listOf("hidden-a"), result.map { it.channelId })
        assertEquals(setOf("group-a", "group-b"), result.single().customGroupIds)
    }

    private fun record(
        channelId: String,
        hidden: Boolean,
        providerOrder: Long,
    ) = LiveChannelRecord(
        channelId = channelId,
        sourceId = "source",
        providerCategoryKey = null,
        categoryName = null,
        providerName = channelId,
        tvgName = null,
        logoRef = null,
        providerOrder = providerOrder,
        availability = ChannelAvailability.AVAILABLE,
        localDisplayName = null,
        logoOverrideRef = null,
        manualOrder = providerOrder,
        favoriteOrder = null,
        hiddenAtEpochMillis = if (hidden) 10 else null,
        recentAtEpochMillis = null,
    )
}
