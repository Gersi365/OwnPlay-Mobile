package app.ownplay.player.personalization

import app.ownplay.player.persistence.ChannelCustomizationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelCustomizationPatcherTest {
    @Test
    fun localRenamePreservesLogoAndManualOrder() {
        val existing = ChannelCustomizationEntity(
            channelId = "channel",
            localDisplayName = "Old",
            logoOverrideRef = "logo-ref",
            manualOrder = 7,
        )

        val updated = ChannelCustomizationPatcher.withLocalDisplayName(
            existing = existing,
            channelId = "channel",
            localDisplayName = "New Name",
        )

        assertEquals("New Name", updated.localDisplayName)
        assertEquals("logo-ref", updated.logoOverrideRef)
        assertEquals(7L, updated.manualOrder)
    }

    @Test
    fun clearingLocalRenamePreservesOtherCustomization() {
        val existing = ChannelCustomizationEntity(
            channelId = "channel",
            localDisplayName = "Old",
            logoOverrideRef = "logo-ref",
            manualOrder = 3,
        )

        val updated = ChannelCustomizationPatcher.withLocalDisplayName(
            existing = existing,
            channelId = "channel",
            localDisplayName = null,
        )

        assertNull(updated.localDisplayName)
        assertEquals("logo-ref", updated.logoOverrideRef)
        assertEquals(3L, updated.manualOrder)
    }

    @Test
    fun logoOverridePreservesRenameAndManualOrder() {
        val existing = ChannelCustomizationEntity(
            channelId = "channel",
            localDisplayName = "My Channel",
            logoOverrideRef = "old-logo",
            manualOrder = 9,
        )

        val updated = ChannelCustomizationPatcher.withLogoOverrideRef(
            existing = existing,
            channelId = "channel",
            logoOverrideRef = "new-logo",
        )

        assertEquals("My Channel", updated.localDisplayName)
        assertEquals("new-logo", updated.logoOverrideRef)
        assertEquals(9L, updated.manualOrder)
    }

    @Test
    fun newCustomizationStartsWithoutUnrelatedValues() {
        val updated = ChannelCustomizationPatcher.withLocalDisplayName(
            existing = null,
            channelId = "channel",
            localDisplayName = "Local",
        )

        assertEquals("Local", updated.localDisplayName)
        assertNull(updated.logoOverrideRef)
        assertNull(updated.manualOrder)
    }

    @Test(expected = IllegalArgumentException::class)
    fun patchRejectsCustomizationBelongingToDifferentChannel() {
        ChannelCustomizationPatcher.withLocalDisplayName(
            existing = ChannelCustomizationEntity(channelId = "other"),
            channelId = "channel",
            localDisplayName = "Name",
        )
    }
}
