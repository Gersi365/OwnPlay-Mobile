package app.ownplay.player.source

internal const val SOURCE_REFRESH_STALE_MILLIS: Long = 6L * 60L * 60L * 1000L

internal fun shouldRefreshSource(
    lastSuccessAtEpochMillis: Long?,
    nowEpochMillis: Long,
    staleAfterMillis: Long = SOURCE_REFRESH_STALE_MILLIS,
): Boolean =
    lastSuccessAtEpochMillis == null ||
        nowEpochMillis < lastSuccessAtEpochMillis ||
        nowEpochMillis - lastSuccessAtEpochMillis >= staleAfterMillis
