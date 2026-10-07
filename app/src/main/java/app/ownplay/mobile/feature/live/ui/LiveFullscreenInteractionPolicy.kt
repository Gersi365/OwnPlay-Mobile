package app.ownplay.mobile.feature.live.ui

import kotlin.math.abs

internal enum class LiveFullscreenGestureAxis {
    HORIZONTAL,
    VERTICAL,
    AMBIGUOUS,
}

internal enum class LiveChannelStep {
    NEXT,
    PREVIOUS,
}

internal object LiveFullscreenInteractionPolicy {
    private const val DEFAULT_DOMINANCE_RATIO = 1.25f

    fun classifyDrag(
        totalDx: Float,
        totalDy: Float,
        minimumDistancePx: Float,
        dominanceRatio: Float = DEFAULT_DOMINANCE_RATIO,
    ): LiveFullscreenGestureAxis {
        require(minimumDistancePx >= 0f)
        require(dominanceRatio >= 1f)

        val x = abs(totalDx)
        val y = abs(totalDy)
        if (x < minimumDistancePx && y < minimumDistancePx) {
            return LiveFullscreenGestureAxis.AMBIGUOUS
        }
        return when {
            x >= minimumDistancePx && x >= y * dominanceRatio ->
                LiveFullscreenGestureAxis.HORIZONTAL
            y >= minimumDistancePx && y >= x * dominanceRatio ->
                LiveFullscreenGestureAxis.VERTICAL
            else ->
                LiveFullscreenGestureAxis.AMBIGUOUS
        }
    }

    fun channelStep(totalDx: Float): LiveChannelStep? = when {
        totalDx < 0f -> LiveChannelStep.NEXT
        totalDx > 0f -> LiveChannelStep.PREVIOUS
        else -> null
    }

    fun adjacentChannelId(
        orderedChannelIds: List<String>,
        currentChannelId: String?,
        lastKnownIndex: Int?,
        step: LiveChannelStep,
    ): String? {
        if (orderedChannelIds.isEmpty()) return null

        val currentIndex = currentChannelId
            ?.let(orderedChannelIds::indexOf)
            ?.takeIf { it >= 0 }

        val targetIndex = if (currentIndex != null) {
            when (step) {
                LiveChannelStep.NEXT -> currentIndex + 1
                LiveChannelStep.PREVIOUS -> currentIndex - 1
            }
        } else {
            val anchor = lastKnownIndex ?: return null
            when (step) {
                // If the playing favorite was removed, the old next item shifts into
                // the old index and the removed channel no longer participates.
                LiveChannelStep.NEXT -> anchor.coerceAtMost(orderedChannelIds.lastIndex)
                LiveChannelStep.PREVIOUS -> (anchor - 1).coerceAtMost(orderedChannelIds.lastIndex)
            }
        }

        return orderedChannelIds.getOrNull(targetIndex)
    }
}
