package app.ownplay.player.personalization

import java.util.UUID
import kotlin.math.abs

data class VisibleChannelBounds(
    val channelId: String,
    val top: Float,
    val bottom: Float,
) {
    val center: Float
        get() = top + ((bottom - top) / 2f)
}

data class ChannelDragTarget(
    val anchorChannelId: String,
    val placement: ManualOrderPlacement,
)

object ChannelDragTargetResolver {
    fun resolve(
        pointerY: Float,
        draggedChannelId: String,
        visibleItems: List<VisibleChannelBounds>,
    ): ChannelDragTarget? {
        if (!pointerY.isFinite() || draggedChannelId.isBlank()) return null

        // Persisted channel IDs are stable UUIDs. LazyColumn headers also use String keys,
        // so when dragging a persisted channel we only accept other stable channel IDs
        // as anchors. Non-UUID IDs remain supported by unit-level/pure callers.
        val draggedUsesStableId = draggedChannelId.isStableChannelId()
        val target = visibleItems
            .asSequence()
            .filter { item ->
                item.channelId.isNotBlank() &&
                    item.channelId != draggedChannelId &&
                    (!draggedUsesStableId || item.channelId.isStableChannelId()) &&
                    item.top.isFinite() &&
                    item.bottom.isFinite() &&
                    item.bottom >= item.top
            }
            .minByOrNull { item -> abs(pointerY - item.center) }
            ?: return null

        return ChannelDragTarget(
            anchorChannelId = target.channelId,
            placement = if (pointerY < target.center) {
                ManualOrderPlacement.BEFORE
            } else {
                ManualOrderPlacement.AFTER
            },
        )
    }
}

private fun String.isStableChannelId(): Boolean = runCatching {
    UUID.fromString(this)
}.isSuccess
