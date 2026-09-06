package app.ownplay.player.epg

internal object EpgCacheFreshness {
    fun isFresh(
        loadedAtEpochSeconds: Long,
        nowEpochSeconds: Long,
        ttlSeconds: Long,
    ): Boolean {
        if (ttlSeconds <= 0L || nowEpochSeconds < loadedAtEpochSeconds) return false
        return nowEpochSeconds - loadedAtEpochSeconds < ttlSeconds
    }
}
