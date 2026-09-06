package app.ownplay.player.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBrowseHierarchyPolicyTest {
    @Test
    fun `tv starts at categories while non tv starts at channels`() {
        assertEquals(
            LiveBrowseHierarchyLevel.CATEGORIES,
            LiveBrowseHierarchyPolicy.initialLevel(isTelevision = true),
        )
        assertEquals(
            LiveBrowseHierarchyLevel.CHANNELS,
            LiveBrowseHierarchyPolicy.initialLevel(isTelevision = false),
        )
    }

    @Test
    fun `tv with active preview restores channel hierarchy`() {
        assertEquals(
            LiveBrowseHierarchyLevel.CHANNELS,
            LiveBrowseHierarchyPolicy.initialLevel(
                isTelevision = true,
                hasPreview = true,
            ),
        )
    }

    @Test
    fun `preview owns back on every device while hierarchy back remains tv only`() {
        assertTrue(
            LiveBrowseHierarchyPolicy.ownsBack(
                isTelevision = true,
                hasPreview = true,
                level = LiveBrowseHierarchyLevel.CATEGORIES,
            ),
        )
        assertTrue(
            LiveBrowseHierarchyPolicy.ownsBack(
                isTelevision = false,
                hasPreview = true,
                level = LiveBrowseHierarchyLevel.CHANNELS,
            ),
        )
        assertTrue(
            LiveBrowseHierarchyPolicy.ownsBack(
                isTelevision = true,
                hasPreview = false,
                level = LiveBrowseHierarchyLevel.CHANNELS,
            ),
        )
        assertFalse(
            LiveBrowseHierarchyPolicy.ownsBack(
                isTelevision = true,
                hasPreview = false,
                level = LiveBrowseHierarchyLevel.CATEGORIES,
            ),
        )
        assertFalse(
            LiveBrowseHierarchyPolicy.ownsBack(
                isTelevision = false,
                hasPreview = false,
                level = LiveBrowseHierarchyLevel.CHANNELS,
            ),
        )
    }

    @Test
    fun `back closes preview before changing browse hierarchy`() {
        assertEquals(
            LiveBrowseBackAction.CLOSE_PREVIEW,
            LiveBrowseHierarchyPolicy.backAction(
                hasPreview = true,
                level = LiveBrowseHierarchyLevel.CHANNELS,
            ),
        )
        assertEquals(
            LiveBrowseBackAction.CLOSE_PREVIEW,
            LiveBrowseHierarchyPolicy.backAction(
                hasPreview = true,
                level = LiveBrowseHierarchyLevel.CATEGORIES,
            ),
        )
    }

    @Test
    fun `back returns channel browsing to categories when preview is closed`() {
        assertEquals(
            LiveBrowseBackAction.SHOW_CATEGORIES,
            LiveBrowseHierarchyPolicy.backAction(
                hasPreview = false,
                level = LiveBrowseHierarchyLevel.CHANNELS,
            ),
        )
    }

    @Test
    fun `back propagates from category root when preview is closed`() {
        assertEquals(
            LiveBrowseBackAction.PROPAGATE,
            LiveBrowseHierarchyPolicy.backAction(
                hasPreview = false,
                level = LiveBrowseHierarchyLevel.CATEGORIES,
            ),
        )
    }

    @Test
    fun `first tv ok opens preview and second ok on same channel opens fullscreen`() {
        assertEquals(
            LiveChannelActivationAction.OPEN_PREVIEW,
            LiveBrowseHierarchyPolicy.channelActivationAction(
                isTelevision = true,
                activePreviewChannelId = null,
                activatedChannelId = "channel-7",
            ),
        )
        assertEquals(
            LiveChannelActivationAction.OPEN_FULLSCREEN,
            LiveBrowseHierarchyPolicy.channelActivationAction(
                isTelevision = true,
                activePreviewChannelId = "channel-7",
                activatedChannelId = "channel-7",
            ),
        )
    }

    @Test
    fun `tv ok on another channel replaces preview instead of opening fullscreen`() {
        assertEquals(
            LiveChannelActivationAction.OPEN_PREVIEW,
            LiveBrowseHierarchyPolicy.channelActivationAction(
                isTelevision = true,
                activePreviewChannelId = "channel-7",
                activatedChannelId = "channel-8",
            ),
        )
    }

    @Test
    fun `non tv repeated activation remains preview behavior`() {
        assertEquals(
            LiveChannelActivationAction.OPEN_PREVIEW,
            LiveBrowseHierarchyPolicy.channelActivationAction(
                isTelevision = false,
                activePreviewChannelId = "channel-7",
                activatedChannelId = "channel-7",
            ),
        )
    }
}
