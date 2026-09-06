package app.ownplay.player.personalization

data class ChannelEditState(
    val isEditing: Boolean = false,
    val selectedChannelIds: Set<String> = emptySet(),
)

sealed interface ChannelBulkAction {
    data object Hide : ChannelBulkAction
    data object Unhide : ChannelBulkAction
    data object Favorite : ChannelBulkAction
    data object RemoveFavorite : ChannelBulkAction
    data object MoveToTop : ChannelBulkAction
    data object MoveToBottom : ChannelBulkAction
    data object MoveFavoritesToTop : ChannelBulkAction
    data object MoveFavoritesToBottom : ChannelBulkAction

    data class AddToGroup(val groupId: String) : ChannelBulkAction {
        init {
            require(groupId.isNotBlank()) { "Group ID must not be blank" }
        }
    }

    data class RemoveFromGroup(val groupId: String) : ChannelBulkAction {
        init {
            require(groupId.isNotBlank()) { "Group ID must not be blank" }
        }
    }
}

object ChannelEditReducer {
    fun enter(state: ChannelEditState): ChannelEditState = state.copy(
        isEditing = true,
        selectedChannelIds = emptySet(),
    )

    fun exit(state: ChannelEditState): ChannelEditState = state.copy(
        isEditing = false,
        selectedChannelIds = emptySet(),
    )

    fun toggleSelection(
        state: ChannelEditState,
        channelId: String,
    ): ChannelEditState {
        if (!state.isEditing || channelId.isBlank()) return state
        val next = state.selectedChannelIds.toMutableSet()
        if (!next.add(channelId)) next.remove(channelId)
        return state.copy(selectedChannelIds = next.toSet())
    }

    fun selectVisible(
        state: ChannelEditState,
        visibleChannelIds: List<String>,
    ): ChannelEditState {
        if (!state.isEditing) return state
        val validIds = visibleChannelIds
            .asSequence()
            .filter(String::isNotBlank)
            .toCollection(linkedSetOf())
        return state.copy(selectedChannelIds = validIds)
    }

    fun clearSelection(state: ChannelEditState): ChannelEditState =
        if (state.isEditing) state.copy(selectedChannelIds = emptySet()) else state

    fun retainAvailable(
        state: ChannelEditState,
        availableChannelIds: Collection<String>,
    ): ChannelEditState {
        if (!state.isEditing || state.selectedChannelIds.isEmpty()) return state
        val available = availableChannelIds.toHashSet()
        return state.copy(
            selectedChannelIds = state.selectedChannelIds.filterTo(linkedSetOf(), available::contains),
        )
    }
}
