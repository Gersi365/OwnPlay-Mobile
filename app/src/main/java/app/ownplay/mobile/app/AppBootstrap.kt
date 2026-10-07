package app.ownplay.mobile.app

import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import app.ownplay.mobile.sources.domain.SourceSummary

/**
 * Root application states that sit above normal Live / Library / Settings navigation.
 *
 * OwnPlay is local-only: normal application navigation is gated exclusively by local source state.
 */
enum class AppBootstrapState {
    BOOTSTRAPPING,
    NEEDS_PROVISIONING,
    NEEDS_SOURCE_SELECTION_OR_REPAIR,
    READY,
}

enum class LocalSourceGateState {
    NONE,
    USABLE_ACTIVE,
    USABLE_NO_ACTIVE,
    NEEDS_REPAIR,
}

object LocalSourceGateResolver {
    fun resolve(
        sources: List<SourceSummary>,
        activeSource: SourceSummary?,
    ): LocalSourceGateState {
        if (activeSource?.isUsableForBootstrap() == true) {
            return LocalSourceGateState.USABLE_ACTIVE
        }
        if (sources.isEmpty()) {
            return LocalSourceGateState.NONE
        }
        if (sources.any { source -> source.isUsableForBootstrap() }) {
            return LocalSourceGateState.USABLE_NO_ACTIVE
        }
        return LocalSourceGateState.NEEDS_REPAIR
    }

    private fun SourceSummary.isUsableForBootstrap(): Boolean =
        enabled && refreshFailureCategory != SourceRefreshFailureCategory.AUTHENTICATION
}

data class AppBootstrapSnapshot(
    val initialized: Boolean,
    val localSourceState: LocalSourceGateState,
)

object AppBootstrapResolver {
    fun resolve(snapshot: AppBootstrapSnapshot): AppBootstrapState {
        if (!snapshot.initialized) return AppBootstrapState.BOOTSTRAPPING

        return when (snapshot.localSourceState) {
            LocalSourceGateState.USABLE_ACTIVE -> AppBootstrapState.READY
            LocalSourceGateState.NONE -> AppBootstrapState.NEEDS_PROVISIONING
            LocalSourceGateState.USABLE_NO_ACTIVE,
            LocalSourceGateState.NEEDS_REPAIR -> AppBootstrapState.NEEDS_SOURCE_SELECTION_OR_REPAIR
        }
    }
}
