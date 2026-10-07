package app.ownplay.mobile.app

enum class AppDestination(
    val label: String,
    val primary: Boolean,
) {
    LIVE("Live", true),
    LIBRARY("Library", true),
    DOWNLOADS("Downloads", false),
    SETTINGS("Settings", true),
    ;

    val bottomNavigationSelection: AppDestination
        get() = if (primary) this else LIBRARY

    val rootBackAction: AppRootBackAction
        get() = if (primary) {
            AppRootBackAction.CONFIRM_EXIT
        } else {
            AppRootBackAction.RETURN_TO_PRIMARY
        }

    companion object {
        val primaryEntries: List<AppDestination>
            get() = entries.filter(AppDestination::primary)
    }
}


enum class AppRootBackAction {
    RETURN_TO_PRIMARY,
    CONFIRM_EXIT,
}
