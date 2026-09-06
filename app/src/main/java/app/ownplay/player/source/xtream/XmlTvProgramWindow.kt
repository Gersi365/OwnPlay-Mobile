package app.ownplay.player.source.xtream

internal object XmlTvProgramWindow {
    fun overlaps(
        startEpochSeconds: Long?,
        stopEpochSeconds: Long?,
        earliestEpochSeconds: Long,
        latestEpochSeconds: Long,
    ): Boolean {
        require(earliestEpochSeconds <= latestEpochSeconds) {
            "XMLTV window start must not be after its end"
        }
        if (startEpochSeconds == null && stopEpochSeconds == null) return false
        if (
            startEpochSeconds != null &&
            stopEpochSeconds != null &&
            stopEpochSeconds <= startEpochSeconds
        ) {
            return false
        }

        val effectiveStart = startEpochSeconds ?: stopEpochSeconds ?: return false
        val effectiveStop = stopEpochSeconds ?: startEpochSeconds ?: return false
        return effectiveStop >= earliestEpochSeconds && effectiveStart <= latestEpochSeconds
    }
}
