package app.ownplay.player.playback

internal object MediaDuration {
    fun secondsToMillis(seconds: Long?): Long? = seconds
        ?.takeIf { it > 0L && it <= Long.MAX_VALUE / 1_000L }
        ?.times(1_000L)
}
