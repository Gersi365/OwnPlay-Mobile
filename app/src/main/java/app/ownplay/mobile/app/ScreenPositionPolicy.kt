package app.ownplay.mobile.app

enum class ScreenFamily { SETTINGS, LIVE, LIBRARY, DOWNLOADS, EPG }
enum class ScreenNavigationEvent { ENTER_PAGE, RETURN_TO_CONTEXT, CHANGE_CONTEXT, REFRESH, OPEN_EPG, SOURCE_CHANGED, RESTORE_CONFIGURATION }
enum class ScreenPositionAction { RESET_TOP, RESTORE_CONTEXT, ANCHOR_CURRENT_EPG }

/** Navigation meaning owns position; elapsed time never expires a browsing context. */
object ScreenPositionPolicy {
    fun resolve(family: ScreenFamily, event: ScreenNavigationEvent): ScreenPositionAction = when {
        family == ScreenFamily.EPG && event in setOf(ScreenNavigationEvent.OPEN_EPG, ScreenNavigationEvent.CHANGE_CONTEXT, ScreenNavigationEvent.SOURCE_CHANGED) -> ScreenPositionAction.ANCHOR_CURRENT_EPG
        event == ScreenNavigationEvent.SOURCE_CHANGED -> ScreenPositionAction.RESET_TOP
        event == ScreenNavigationEvent.REFRESH || event == ScreenNavigationEvent.RESTORE_CONFIGURATION -> ScreenPositionAction.RESTORE_CONTEXT
        family == ScreenFamily.SETTINGS -> ScreenPositionAction.RESET_TOP
        event == ScreenNavigationEvent.RETURN_TO_CONTEXT -> ScreenPositionAction.RESTORE_CONTEXT
        else -> ScreenPositionAction.RESET_TOP
    }
}

/** Only the currently composed editor may intercept a Settings exit. */
class SettingsExitGuard {
    private var owner: Any? = null
    private var interceptor: (((() -> Unit)) -> Unit)? = null

    fun register(owner: Any, interceptor: (() -> Unit) -> Unit) {
        this.owner = owner
        this.interceptor = interceptor
    }

    fun unregister(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            interceptor = null
        }
    }

    fun requestExit(continueNavigation: () -> Unit) {
        val handler = interceptor
        if (handler == null) continueNavigation() else handler(continueNavigation)
    }
}
