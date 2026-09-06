package app.ownplay.player.playback

enum class PlaybackNetworkState {
    AVAILABLE,
    UNAVAILABLE,
}

enum class PlaybackNetworkTransport {
    WIFI,
    ETHERNET,
    CELLULAR,
    VPN,
    BLUETOOTH,
}

data class PlaybackNetworkProfile(
    val state: PlaybackNetworkState,
    val validated: Boolean = false,
    val metered: Boolean? = null,
    val downstreamBandwidthKbps: Int? = null,
    val upstreamBandwidthKbps: Int? = null,
    val transports: Set<PlaybackNetworkTransport> = emptySet(),
    val measuredPlaybackBandwidthBps: Long? = null,
) {
    init {
        require(downstreamBandwidthKbps == null || downstreamBandwidthKbps > 0) {
            "Downstream bandwidth must be null or positive"
        }
        require(upstreamBandwidthKbps == null || upstreamBandwidthKbps > 0) {
            "Upstream bandwidth must be null or positive"
        }
        require(measuredPlaybackBandwidthBps == null || measuredPlaybackBandwidthBps > 0L) {
            "Measured playback bandwidth must be null or positive"
        }
        if (state == PlaybackNetworkState.UNAVAILABLE) {
            require(!validated) { "Unavailable network cannot be validated" }
            require(metered == null) { "Unavailable network cannot report metering" }
            require(downstreamBandwidthKbps == null) {
                "Unavailable network cannot report downstream bandwidth"
            }
            require(upstreamBandwidthKbps == null) {
                "Unavailable network cannot report upstream bandwidth"
            }
            require(transports.isEmpty()) { "Unavailable network cannot report transports" }
            require(measuredPlaybackBandwidthBps == null) {
                "Unavailable network cannot retain measured playback bandwidth"
            }
        }
    }

    companion object {
        val Unavailable = PlaybackNetworkProfile(PlaybackNetworkState.UNAVAILABLE)
    }
}

data class PlaybackRetryPolicy(
    val maxAutomaticAttempts: Int = 3,
    val initialDelayMillis: Long = 750L,
    val maxDelayMillis: Long = 3_000L,
) {
    init {
        require(maxAutomaticAttempts >= 0) { "Automatic retry attempts must not be negative" }
        require(initialDelayMillis >= 0L) { "Initial retry delay must not be negative" }
        require(maxDelayMillis >= initialDelayMillis) {
            "Maximum retry delay must be greater than or equal to initial delay"
        }
    }

    fun delayBeforeAttempt(attempt: Int): Long {
        require(attempt > 0) { "Retry attempt must be positive" }
        var delayMillis = initialDelayMillis
        repeat(attempt - 1) {
            if (delayMillis < maxDelayMillis) {
                delayMillis = if (delayMillis > maxDelayMillis / 2L) {
                    maxDelayMillis
                } else {
                    (delayMillis * 2L).coerceAtMost(maxDelayMillis)
                }
            }
        }
        return delayMillis
    }
}
