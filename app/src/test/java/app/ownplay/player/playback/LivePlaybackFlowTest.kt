package app.ownplay.player.playback

import app.ownplay.player.live.LiveChannelItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackFlowTest {
    @Test
    fun normalChannelTapCreatesOpaquePlaybackRequest() {
        val channel = channel(
            sourceId = "source-secret-identity",
            channelId = "channel-secret-identity",
            displayName = "News",
        )

        val action = LiveChannelSelectionRouter.route(
            channel = channel,
            isEditing = false,
        ) as LiveChannelSelectionAction.StartPlayback

        assertEquals("source-secret-identity", action.selection.request.sourceId)
        assertEquals("channel-secret-identity", action.selection.request.channelId)
        assertEquals(PlaybackMediaKind.LIVE, action.selection.request.mediaKind)
        assertEquals("News", action.selection.displayName)
        assertNull(action.selection.request.navigationContext)

        val rendered = action.selection.toString()
        assertFalse(rendered.contains("source-secret-identity"))
        assertFalse(rendered.contains("channel-secret-identity"))
        assertFalse(rendered.contains("http://"))
        assertFalse(rendered.contains("https://"))
    }

    @Test
    fun visibleBrowseOrderCreatesDeterministicPreviousAndNextContext() {
        val channels = listOf(
            channel(channelId = "one", displayName = "One"),
            channel(channelId = "two", displayName = "Two"),
            channel(channelId = "three", displayName = "Three"),
        )
        val context = LivePlaybackBrowseContext.capture("source", channels)

        val selection = context.selectionFor("two") ?: error("missing selection")

        assertEquals("one", selection.request.navigationTarget(PlaybackNavigationDirection.PREVIOUS))
        assertEquals("three", selection.request.navigationTarget(PlaybackNavigationDirection.NEXT))
        assertEquals("Two", selection.displayName)
    }

    @Test
    fun navigationReusesCapturedSnapshotAndUpdatesNeighbors() {
        val channels = listOf(
            channel(channelId = "one", displayName = "One"),
            channel(channelId = "two", displayName = "Two"),
            channel(channelId = "three", displayName = "Three"),
        )
        val context = LivePlaybackBrowseContext.capture("source", channels)
        val middle = context.selectionFor("two") ?: error("missing middle")

        val previous = middle.navigate(PlaybackNavigationDirection.PREVIOUS)
            ?: error("missing previous")
        val next = middle.navigate(PlaybackNavigationDirection.NEXT)
            ?: error("missing next")

        assertEquals("one", previous.request.channelId)
        assertNull(previous.request.navigationTarget(PlaybackNavigationDirection.PREVIOUS))
        assertEquals("two", previous.request.navigationTarget(PlaybackNavigationDirection.NEXT))
        assertEquals("One", previous.displayName)

        assertEquals("three", next.request.channelId)
        assertEquals("two", next.request.navigationTarget(PlaybackNavigationDirection.PREVIOUS))
        assertNull(next.request.navigationTarget(PlaybackNavigationDirection.NEXT))
        assertEquals("Three", next.displayName)
    }

    @Test
    fun filteredSnapshotDoesNotNavigateToChannelsOutsideVisibleResults() {
        val fullCatalog = listOf(
            channel(channelId = "one"),
            channel(channelId = "two"),
            channel(channelId = "three"),
            channel(channelId = "four"),
        )
        val visibleSearchResults = listOf(fullCatalog[1], fullCatalog[3])
        val context = LivePlaybackBrowseContext.capture("source", visibleSearchResults)

        val second = context.selectionFor("two") ?: error("missing selection")
        val fourth = second.navigate(PlaybackNavigationDirection.NEXT)
            ?: error("missing next")

        assertNull(second.request.navigationTarget(PlaybackNavigationDirection.PREVIOUS))
        assertEquals("four", second.request.navigationTarget(PlaybackNavigationDirection.NEXT))
        assertEquals("two", fourth.request.navigationTarget(PlaybackNavigationDirection.PREVIOUS))
        assertNull(fourth.request.navigationTarget(PlaybackNavigationDirection.NEXT))
    }

    @Test
    fun capturedContextIgnoresOtherSourcesAndDuplicateIds() {
        val context = LivePlaybackBrowseContext.capture(
            sourceId = "source-a",
            visibleChannels = listOf(
                channel(sourceId = "source-a", channelId = "one", displayName = "First"),
                channel(sourceId = "source-b", channelId = "foreign", displayName = "Foreign"),
                channel(sourceId = "source-a", channelId = "one", displayName = "Duplicate"),
                channel(sourceId = "source-a", channelId = "two", displayName = "Second"),
            ),
        )

        assertEquals(2, context.entries.size)
        assertEquals("First", context.entries[0].displayName)
        assertEquals("Second", context.entries[1].displayName)
        assertNull(context.selectionFor("foreign"))
    }

    @Test
    fun browseContextAndSelectionRenderingRedactOpaqueIdentity() {
        val context = LivePlaybackBrowseContext.capture(
            sourceId = "source-secret-identity",
            visibleChannels = listOf(
                channel(
                    sourceId = "source-secret-identity",
                    channelId = "channel-secret-one",
                    displayName = "One",
                ),
                channel(
                    sourceId = "source-secret-identity",
                    channelId = "channel-secret-two",
                    displayName = "Two",
                ),
            ),
        )
        val selection = context.selectionFor("channel-secret-one") ?: error("missing selection")
        val rendered = context.toString() + selection.toString()

        assertFalse(rendered.contains("source-secret-identity"))
        assertFalse(rendered.contains("channel-secret-one"))
        assertFalse(rendered.contains("channel-secret-two"))
    }

    @Test
    fun editModeTapNeverCreatesPlaybackRequest() {
        val channel = channel()
        val context = LivePlaybackBrowseContext.capture("source", listOf(channel))
        val action = LiveChannelSelectionRouter.route(
            channel = channel,
            isEditing = true,
            browseContext = context,
        )

        assertTrue(action is LiveChannelSelectionAction.ToggleEditSelection)
        assertFalse(action is LiveChannelSelectionAction.StartPlayback)
    }

    @Test
    fun selectedPlaybackIdentityRemainsStableAfterBrowseItemChanges() {
        val original = channel(
            sourceId = "source-a",
            channelId = "channel-a",
            displayName = "Original name",
        )
        val selection = LivePlaybackSelection.from(original)

        val changedBrowseItem = original.copy(
            sourceId = "source-b",
            channelId = "channel-b",
            displayName = "Changed name",
        )

        assertEquals("source-a", selection.request.sourceId)
        assertEquals("channel-a", selection.request.channelId)
        assertEquals("Original name", selection.displayName)
        assertEquals("source-b", changedBrowseItem.sourceId)
        assertEquals("channel-b", changedBrowseItem.channelId)
    }

    @Test
    fun blankDisplayNameFallsBackWithoutChangingIdentity() {
        val selection = LivePlaybackSelection.from(
            channel(
                sourceId = "source-a",
                channelId = "channel-a",
                displayName = "   ",
            ),
        )

        assertEquals("Live channel", selection.displayName)
        assertEquals("source-a", selection.request.sourceId)
        assertEquals("channel-a", selection.request.channelId)
    }

    private fun channel(
        sourceId: String = "source",
        channelId: String = "channel",
        displayName: String = "Channel",
    ): LiveChannelItem = LiveChannelItem(
        channelId = channelId,
        sourceId = sourceId,
        categoryKey = null,
        categoryName = null,
        providerName = displayName,
        localDisplayName = null,
        displayName = displayName,
        logoRef = null,
        hasLogoOverride = false,
        providerOrder = 0L,
        manualOrder = null,
        favoriteOrder = null,
        isFavorite = false,
        isHidden = false,
        availability = "available",
        recentAtEpochMillis = null,
    )
}
