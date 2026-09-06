package app.ownplay.player.personalization

sealed interface ChannelBulkActionExecutionResult {
    data class Visibility(val result: ChannelVisibilityMutationResult) : ChannelBulkActionExecutionResult

    data class Favorite(val result: FavoriteMutationResult) : ChannelBulkActionExecutionResult

    data class ManualOrder(val result: ManualOrderMutationResult) : ChannelBulkActionExecutionResult

    data class CustomGroup(val result: CustomGroupMutationResult) : ChannelBulkActionExecutionResult
}

class ChannelBulkActionExecutor(
    private val visibilityMutator: ChannelVisibilityMutator,
    private val favoriteMutator: FavoriteChannelMutator,
    private val manualOrderMutator: ManualChannelOrderMutator,
    private val customGroupMutator: CustomGroupMutator,
) {
    suspend fun execute(
        sourceId: String,
        selectedChannelIds: Set<String>,
        action: ChannelBulkAction,
        eventAtEpochMillis: Long,
    ): ChannelBulkActionExecutionResult = when (action) {
        ChannelBulkAction.Hide -> ChannelBulkActionExecutionResult.Visibility(
            visibilityMutator.hide(
                sourceId = sourceId,
                channelIds = selectedChannelIds,
                hiddenAtEpochMillis = eventAtEpochMillis,
            ),
        )

        ChannelBulkAction.Unhide -> ChannelBulkActionExecutionResult.Visibility(
            visibilityMutator.unhide(
                sourceId = sourceId,
                channelIds = selectedChannelIds,
            ),
        )

        ChannelBulkAction.Favorite -> ChannelBulkActionExecutionResult.Favorite(
            favoriteMutator.addFavorites(
                sourceId = sourceId,
                channelIds = selectedChannelIds,
                addedAtEpochMillis = eventAtEpochMillis,
            ),
        )

        ChannelBulkAction.RemoveFavorite -> ChannelBulkActionExecutionResult.Favorite(
            favoriteMutator.removeFavorites(
                sourceId = sourceId,
                channelIds = selectedChannelIds,
            ),
        )

        ChannelBulkAction.MoveToTop -> ChannelBulkActionExecutionResult.ManualOrder(
            manualOrderMutator.moveSelectedToTop(
                sourceId = sourceId,
                selectedChannelIds = selectedChannelIds,
            ),
        )

        ChannelBulkAction.MoveToBottom -> ChannelBulkActionExecutionResult.ManualOrder(
            manualOrderMutator.moveSelectedToBottom(
                sourceId = sourceId,
                selectedChannelIds = selectedChannelIds,
            ),
        )

        ChannelBulkAction.MoveFavoritesToTop -> ChannelBulkActionExecutionResult.Favorite(
            favoriteMutator.moveSelectedFavoritesToTop(
                sourceId = sourceId,
                channelIds = selectedChannelIds,
            ),
        )

        ChannelBulkAction.MoveFavoritesToBottom -> ChannelBulkActionExecutionResult.Favorite(
            favoriteMutator.moveSelectedFavoritesToBottom(
                sourceId = sourceId,
                channelIds = selectedChannelIds,
            ),
        )

        is ChannelBulkAction.AddToGroup -> ChannelBulkActionExecutionResult.CustomGroup(
            customGroupMutator.addChannels(
                sourceId = sourceId,
                groupId = action.groupId,
                channelIds = selectedChannelIds,
            ),
        )

        is ChannelBulkAction.RemoveFromGroup -> ChannelBulkActionExecutionResult.CustomGroup(
            customGroupMutator.removeChannels(
                sourceId = sourceId,
                groupId = action.groupId,
                channelIds = selectedChannelIds,
            ),
        )
    }
}
