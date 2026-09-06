package app.ownplay.player.persistence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistenceContractTest {
    @Test
    fun playlistSourceRenderingDoesNotExposeOpaqueRefs() {
        val entity = PlaylistSourceEntity(
            sourceId = "source-1",
            name = "Example",
            sourceKind = SourceKinds.REMOTE_M3U,
            locatorRef = "secret-locator-ref",
            credentialRef = "secret-credential-ref",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
        )

        val rendered = entity.toString()

        assertTrue(rendered.contains("<opaque>"))
        assertFalse(rendered.contains("secret-locator-ref"))
        assertFalse(rendered.contains("secret-credential-ref"))
    }

    @Test
    fun providerAndManualOrderingRemainSeparateFields() {
        val provider = ProviderChannelEntity(
            channelId = "channel-1",
            sourceId = "source-1",
            providerKey = "provider-key",
            providerName = "Provider Name",
            streamLocatorRef = "stream-ref",
            providerOrder = 7L,
            lastSeenGeneration = 1L,
        )
        val customization = ChannelCustomizationEntity(
            channelId = "channel-1",
            manualOrder = 100L,
        )

        assertTrue(provider.providerOrder == 7L)
        assertTrue(customization.manualOrder == 100L)
    }
}
