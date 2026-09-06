package app.ownplay.player.ui.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped, transient owner for Offline Library playback presentation.
 *
 * Activity recreation can reattach the Library player to an already-running playback session,
 * while process death still starts with no presentation and never introduces cold-start autoplay.
 */
internal object LibraryPlaybackPresentationSession {
    private val _state = MutableStateFlow<LibraryPlaybackSession?>(null)
    val state: StateFlow<LibraryPlaybackSession?> = _state.asStateFlow()

    fun show(session: LibraryPlaybackSession) {
        _state.value = session
    }

    fun clear() {
        _state.value = null
    }
}
