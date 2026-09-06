package app.ownplay.player.personalization

import app.ownplay.player.persistence.CustomGroupMembershipEntity

data class CustomGroupMembershipPlan(
    val memberships: List<CustomGroupMembershipEntity>,
) {
    val channelIds: List<String>
        get() = memberships.map(CustomGroupMembershipEntity::channelId)
}

object CustomGroupMembershipPlanner {
    fun add(
        groupId: String,
        existing: List<CustomGroupMembershipEntity>,
        selectedChannelIdsInSourceOrder: List<String>,
    ): CustomGroupMembershipPlan {
        require(groupId.isNotBlank()) { "Group ID must not be blank" }
        require(existing.all { it.groupId == groupId }) {
            "Existing memberships must belong to the requested group"
        }
        val existingByChannel = existing.associateBy(CustomGroupMembershipEntity::channelId)
        val orderedIds = existing.map(CustomGroupMembershipEntity::channelId) +
            selectedChannelIdsInSourceOrder.filterNot(existingByChannel::containsKey)
        return buildPlan(groupId, orderedIds)
    }

    fun remove(
        groupId: String,
        existing: List<CustomGroupMembershipEntity>,
        channelIdsToRemove: Set<String>,
    ): CustomGroupMembershipPlan {
        require(groupId.isNotBlank()) { "Group ID must not be blank" }
        require(existing.all { it.groupId == groupId }) {
            "Existing memberships must belong to the requested group"
        }
        val remaining = existing
            .map(CustomGroupMembershipEntity::channelId)
            .filterNot(channelIdsToRemove::contains)
        return buildPlan(groupId, remaining)
    }

    private fun buildPlan(
        groupId: String,
        orderedChannelIds: List<String>,
    ): CustomGroupMembershipPlan = CustomGroupMembershipPlan(
        memberships = orderedChannelIds.mapIndexed { index, channelId ->
            CustomGroupMembershipEntity(
                groupId = groupId,
                channelId = channelId,
                groupOrder = index.toLong(),
            )
        },
    )
}
