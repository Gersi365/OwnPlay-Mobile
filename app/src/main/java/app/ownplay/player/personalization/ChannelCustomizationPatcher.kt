package app.ownplay.player.personalization

import app.ownplay.player.persistence.ChannelCustomizationEntity

object ChannelCustomizationPatcher {
    fun withLocalDisplayName(
        existing: ChannelCustomizationEntity?,
        channelId: String,
        localDisplayName: String?,
    ): ChannelCustomizationEntity = base(existing, channelId).copy(
        localDisplayName = localDisplayName,
    )

    fun withLogoOverrideRef(
        existing: ChannelCustomizationEntity?,
        channelId: String,
        logoOverrideRef: String?,
    ): ChannelCustomizationEntity = base(existing, channelId).copy(
        logoOverrideRef = logoOverrideRef,
    )

    private fun base(
        existing: ChannelCustomizationEntity?,
        channelId: String,
    ): ChannelCustomizationEntity {
        require(channelId.isNotBlank()) { "Channel ID must not be blank" }
        require(existing == null || existing.channelId == channelId) {
            "Existing customization must belong to the requested channel"
        }
        return existing ?: ChannelCustomizationEntity(channelId = channelId)
    }
}
