package app.ownplay.player.personalization

import app.ownplay.player.persistence.CustomGroupMembershipEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomGroupMembershipPlannerTest {
    @Test
    fun addAppendsOnlyNewChannelsInSourceOrder() {
        val existing = listOf(
            CustomGroupMembershipEntity("group", "existing", 0),
        )

        val plan = CustomGroupMembershipPlanner.add(
            groupId = "group",
            existing = existing,
            selectedChannelIdsInSourceOrder = listOf("new-two", "existing", "new-four"),
        )

        assertEquals(listOf("existing", "new-two", "new-four"), plan.channelIds)
        assertEquals(listOf(0L, 1L, 2L), plan.memberships.map { it.groupOrder })
    }

    @Test
    fun addingExistingMembershipIsIdempotent() {
        val existing = listOf(
            CustomGroupMembershipEntity("group", "one", 0),
            CustomGroupMembershipEntity("group", "two", 1),
        )

        val plan = CustomGroupMembershipPlanner.add(
            groupId = "group",
            existing = existing,
            selectedChannelIdsInSourceOrder = listOf("two"),
        )

        assertEquals(existing, plan.memberships)
    }

    @Test
    fun removeNormalizesMembershipOrder() {
        val existing = listOf(
            CustomGroupMembershipEntity("group", "one", 0),
            CustomGroupMembershipEntity("group", "two", 4),
            CustomGroupMembershipEntity("group", "three", 9),
        )

        val plan = CustomGroupMembershipPlanner.remove(
            groupId = "group",
            existing = existing,
            channelIdsToRemove = setOf("two"),
        )

        assertEquals(listOf("one", "three"), plan.channelIds)
        assertEquals(listOf(0L, 1L), plan.memberships.map { it.groupOrder })
    }

    @Test(expected = IllegalArgumentException::class)
    fun plannerRejectsMembershipsFromAnotherGroup() {
        CustomGroupMembershipPlanner.add(
            groupId = "group",
            existing = listOf(CustomGroupMembershipEntity("other", "one", 0)),
            selectedChannelIdsInSourceOrder = listOf("two"),
        )
    }
}
