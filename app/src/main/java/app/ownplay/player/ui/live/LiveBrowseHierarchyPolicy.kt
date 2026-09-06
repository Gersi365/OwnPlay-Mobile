package app.ownplay.player.ui.live

internal enum class LiveBrowseHierarchyLevel {
    CATEGORIES,
    CHANNELS,
}

internal enum class LiveBrowseBackAction {
    CLOSE_PREVIEW,
    SHOW_CATEGORIES,
    PROPAGATE,
}

internal enum class LiveChannelActivationAction {
    OPEN_PREVIEW,
    OPEN_FULLSCREEN,
}

internal object LiveBrowseHierarchyPolicy {
    fun initialLevel(
        isTelevision: Boolean,
        hasPreview: Boolean = false,
    ): LiveBrowseHierarchyLevel = if (isTelevision && !hasPreview) {
        LiveBrowseHierarchyLevel.CATEGORIES
    } else {
        LiveBrowseHierarchyLevel.CHANNELS
    }

    fun ownsBack(
        isTelevision: Boolean,
        hasPreview: Boolean,
        level: LiveBrowseHierarchyLevel,
    ): Boolean = hasPreview || (
        isTelevision && level == LiveBrowseHierarchyLevel.CHANNELS
    )

    fun backAction(
        hasPreview: Boolean,
        level: LiveBrowseHierarchyLevel,
    ): LiveBrowseBackAction = when {
        hasPreview -> LiveBrowseBackAction.CLOSE_PREVIEW
        level == LiveBrowseHierarchyLevel.CHANNELS -> LiveBrowseBackAction.SHOW_CATEGORIES
        else -> LiveBrowseBackAction.PROPAGATE
    }

    fun channelActivationAction(
        isTelevision: Boolean,
        activePreviewChannelId: String?,
        activatedChannelId: String,
    ): LiveChannelActivationAction = if (
        isTelevision && activePreviewChannelId == activatedChannelId
    ) {
        LiveChannelActivationAction.OPEN_FULLSCREEN
    } else {
        LiveChannelActivationAction.OPEN_PREVIEW
    }
}
