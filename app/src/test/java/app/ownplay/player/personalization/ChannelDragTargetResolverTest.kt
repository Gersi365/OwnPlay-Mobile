package app.ownplay.player.personalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelDragTargetResolverTest {
    @Test
    fun choosesNearestNonDraggedChannelAndPlacesBeforeCenter() {
        val result = ChannelDragTargetResolver.resolve(
            pointerY = 124f,
            draggedChannelId = "two",
            visibleItems = listOf(
                VisibleChannelBounds("one", top = 0f, bottom = 60f),
                VisibleChannelBounds("two", top = 60f, bottom = 120f),
                VisibleChannelBounds("three", top = 120f, bottom = 180f),
            ),
        )

        assertEquals(
            ChannelDragTarget(
                anchorChannelId = "three",
                placement = ManualOrderPlacement.BEFORE,
            ),
            result,
        )
    }

    @Test
    fun persistedChannelDragIgnoresStringLazyLayoutKeys() {
        val dragged = "00000000-0000-0000-0000-000000000001"
        val target = "00000000-0000-0000-0000-000000000002"
        val result = ChannelDragTargetResolver.resolve(
            pointerY = 30f,
            draggedChannelId = dragged,
            visibleItems = listOf(
                VisibleChannelBounds("browse-header", top = 0f, bottom = 60f),
                VisibleChannelBounds(target, top = 60f, bottom = 120f),
                VisibleChannelBounds(dragged, top = 120f, bottom = 180f),
            ),
        )

        assertEquals(
            ChannelDragTarget(
                anchorChannelId = target,
                placement = ManualOrderPlacement.BEFORE,
            ),
            result,
        )
    }

    @Test
    fun pointerAtOrBelowCenterPlacesAfterAnchor() {
        val result = ChannelDragTargetResolver.resolve(
            pointerY = 150f,
            draggedChannelId = "one",
            visibleItems = listOf(
                VisibleChannelBounds("one", top = 0f, bottom = 60f),
                VisibleChannelBounds("three", top = 120f, bottom = 180f),
            ),
        )

        assertEquals(
            ChannelDragTarget(
                anchorChannelId = "three",
                placement = ManualOrderPlacement.AFTER,
            ),
            result,
        )
    }

    @Test
    fun invalidBoundsAreIgnored() {
        val result = ChannelDragTargetResolver.resolve(
            pointerY = 90f,
            draggedChannelId = "dragged",
            visibleItems = listOf(
                VisibleChannelBounds("bad-reversed", top = 100f, bottom = 80f),
                VisibleChannelBounds("bad-nan", top = Float.NaN, bottom = 120f),
                VisibleChannelBounds("valid", top = 60f, bottom = 120f),
            ),
        )

        assertEquals(
            ChannelDragTarget(
                anchorChannelId = "valid",
                placement = ManualOrderPlacement.AFTER,
            ),
            result,
        )
    }

    @Test
    fun onlyDraggedVisibleItemReturnsNull() {
        val result = ChannelDragTargetResolver.resolve(
            pointerY = 30f,
            draggedChannelId = "one",
            visibleItems = listOf(VisibleChannelBounds("one", top = 0f, bottom = 60f)),
        )

        assertNull(result)
    }

    @Test
    fun nonFinitePointerReturnsNull() {
        val result = ChannelDragTargetResolver.resolve(
            pointerY = Float.POSITIVE_INFINITY,
            draggedChannelId = "one",
            visibleItems = listOf(VisibleChannelBounds("two", top = 0f, bottom = 60f)),
        )

        assertNull(result)
    }
}
