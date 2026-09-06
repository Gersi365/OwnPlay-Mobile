package app.ownplay.player.ui

internal object OfflineMediaTvFocusPolicy {
    fun preferredVisibleKey(
        visibleKeys: List<String>,
        rememberedKey: String?,
    ): String? {
        if (visibleKeys.isEmpty()) return null
        return rememberedKey?.takeIf(visibleKeys::contains) ?: visibleKeys.first()
    }
}
