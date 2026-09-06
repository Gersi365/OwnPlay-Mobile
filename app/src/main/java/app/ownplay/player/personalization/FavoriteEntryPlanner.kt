package app.ownplay.player.personalization

import app.ownplay.player.persistence.FavoriteEntryEntity

data class FavoriteEntryPlan(
    val entries: List<FavoriteEntryEntity>,
) {
    val channelIds: List<String>
        get() = entries.map(FavoriteEntryEntity::channelId)
}

object FavoriteEntryPlanner {
    fun add(
        existing: List<FavoriteEntryEntity>,
        selectedChannelIdsInSourceOrder: List<String>,
        addedAtEpochMillis: Long,
    ): FavoriteEntryPlan {
        val existingById = existing.associateBy(FavoriteEntryEntity::channelId)
        val existingIds = existing.map(FavoriteEntryEntity::channelId)
        val newIds = selectedChannelIdsInSourceOrder.filterNot(existingById::containsKey)
        return buildPlan(
            orderedChannelIds = existingIds + newIds,
            existingById = existingById,
            newAddedAtEpochMillis = addedAtEpochMillis,
        )
    }

    fun remove(
        existing: List<FavoriteEntryEntity>,
        channelIdsToRemove: Set<String>,
    ): FavoriteEntryPlan {
        val existingById = existing.associateBy(FavoriteEntryEntity::channelId)
        val remainingIds = existing
            .map(FavoriteEntryEntity::channelId)
            .filterNot(channelIdsToRemove::contains)
        return buildPlan(
            orderedChannelIds = remainingIds,
            existingById = existingById,
            newAddedAtEpochMillis = 0,
        )
    }

    fun reorder(
        existing: List<FavoriteEntryEntity>,
        manualOrderPlan: ManualOrderPlan,
    ): FavoriteEntryPlan {
        val existingById = existing.associateBy(FavoriteEntryEntity::channelId)
        require(manualOrderPlan.channelIds.toSet() == existingById.keys) {
            "Favorite reorder plan must contain exactly the existing favorite IDs"
        }
        return buildPlan(
            orderedChannelIds = manualOrderPlan.channelIds,
            existingById = existingById,
            newAddedAtEpochMillis = 0,
        )
    }

    private fun buildPlan(
        orderedChannelIds: List<String>,
        existingById: Map<String, FavoriteEntryEntity>,
        newAddedAtEpochMillis: Long,
    ): FavoriteEntryPlan = FavoriteEntryPlan(
        entries = orderedChannelIds.mapIndexed { index, channelId ->
            existingById[channelId]
                ?.copy(favoriteOrder = index.toLong())
                ?: FavoriteEntryEntity(
                    channelId = channelId,
                    favoriteOrder = index.toLong(),
                    addedAtEpochMillis = newAddedAtEpochMillis,
                )
        },
    )
}
