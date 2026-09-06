package app.ownplay.player.live.ingest

import app.ownplay.player.source.xtream.XtreamLiveStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackLocatorDescriptorTest {
    @Test
    fun xtreamLiveRequiresPositiveStreamId() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackLocatorDescriptor.xtreamLive(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackLocatorDescriptor.xtreamLive(-1)
        }
    }

    @Test
    fun xtreamLiveEncodesPositiveStreamId() {
        assertEquals(
            "ownplay-locator-v1|xtream-live|42",
            PlaybackLocatorDescriptor.xtreamLive(42),
        )
    }

    @Test
    fun catalogFactorySkipsInvalidXtreamStreamIds() {
        val valid = XtreamLiveStream(
            streamId = 42,
            name = "Valid",
            categoryId = null,
            iconUrl = null,
            epgChannelId = null,
            archiveDurationDays = null,
            directSource = null,
        )
        val invalid = valid.copy(streamId = 0, name = "Invalid")

        val catalog = InitialLiveCatalogFactory.fromXtream(
            categories = emptyList(),
            streams = listOf(invalid, valid),
        )

        assertEquals(listOf("42"), catalog.channels.map { it.providerStreamId })
    }
}
