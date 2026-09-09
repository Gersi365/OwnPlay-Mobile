package app.ownplay.mobile.core.playback

data class LiveSelection(
    val sourceId: String,
    val channelId: String,
)

enum class LivePresentation {
    BROWSING,
    PREVIEW,
    FULLSCREEN,
}

sealed interface LiveActivationDecision {
    data class OpenPreview(val selection: LiveSelection) : LiveActivationDecision
    data class OpenFullscreen(val selection: LiveSelection) : LiveActivationDecision
}

object LiveActivationReducer {
    fun activate(
        currentSelection: LiveSelection?,
        presentation: LivePresentation,
        activatedSelection: LiveSelection,
    ): LiveActivationDecision {
        val sameSelection = currentSelection == activatedSelection
        return if (sameSelection && presentation == LivePresentation.PREVIEW) {
            LiveActivationDecision.OpenFullscreen(activatedSelection)
        } else {
            LiveActivationDecision.OpenPreview(activatedSelection)
        }
    }
}
