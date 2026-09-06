package app.ownplay.player.source

internal enum class OnDemandCatalogRefreshMode {
    AUTOMATIC,
    MANUAL,
}

internal class OnDemandCatalogRefreshInvocationGate {
    private var activeSourceId: String? = null

    @Synchronized
    fun nextMode(sourceId: String): OnDemandCatalogRefreshMode =
        if (activeSourceId != sourceId) {
            activeSourceId = sourceId
            OnDemandCatalogRefreshMode.AUTOMATIC
        } else {
            OnDemandCatalogRefreshMode.MANUAL
        }
}

internal fun shouldRefreshOnDemandCatalog(
    mode: OnDemandCatalogRefreshMode,
    lastSuccessAtEpochMillis: Long?,
    nowEpochMillis: Long,
): Boolean =
    mode == OnDemandCatalogRefreshMode.MANUAL ||
        shouldRefreshSource(
            lastSuccessAtEpochMillis = lastSuccessAtEpochMillis,
            nowEpochMillis = nowEpochMillis,
        )
