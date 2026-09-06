package app.ownplay.player.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface PlaybackConnectivityMonitor : AutoCloseable {
    val state: StateFlow<PlaybackNetworkState>
    val profile: StateFlow<PlaybackNetworkProfile>
}

@OptIn(UnstableApi::class)
class AndroidPlaybackConnectivityMonitor(
    context: Context,
    private val networkLossGraceMillis: Long = DEFAULT_NETWORK_LOSS_GRACE_MILLIS,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : PlaybackConnectivityMonitor {
    private val applicationContext = context.applicationContext
    private val connectivityManager = requireNotNull(
        applicationContext.getSystemService(ConnectivityManager::class.java),
    )
    private val bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(applicationContext)
    private var activeNetwork: Network? = connectivityManager.activeNetwork
    private var measuredPlaybackBandwidthBps: Long? = null
    private val mutableProfile = MutableStateFlow(currentProfile())
    private val mutableState = MutableStateFlow(mutableProfile.value.state)
    private var registered = false
    private var pendingLossConfirmation: Runnable? = null

    override val state: StateFlow<PlaybackNetworkState> = mutableState.asStateFlow()
    override val profile: StateFlow<PlaybackNetworkProfile> = mutableProfile.asStateFlow()

    private val bandwidthListener = object : BandwidthMeter.EventListener {
        override fun onBandwidthSample(
            elapsedMs: Int,
            bytesTransferred: Long,
            bitrateEstimate: Long,
        ) {
            if (bitrateEstimate <= 0L || mutableProfile.value.state != PlaybackNetworkState.AVAILABLE) {
                return
            }
            measuredPlaybackBandwidthBps = bitrateEstimate
            publish(
                mutableProfile.value.copy(
                    measuredPlaybackBandwidthBps = bitrateEstimate,
                ),
            )
        }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            publishNetwork(network)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            cancelPendingLossConfirmation()
            observeNetwork(network)
            publish(connectedProfile(networkCapabilities))
        }

        override fun onLost(network: Network) {
            val active = connectivityManager.activeNetwork
            if (active != null && active != network) {
                publishNetwork(active)
            } else {
                scheduleLossConfirmation()
            }
        }
    }

    init {
        require(networkLossGraceMillis >= 0L) {
            "Network loss grace period must not be negative"
        }
        bandwidthMeter.addEventListener(handler, bandwidthListener)
        connectivityManager.registerDefaultNetworkCallback(callback)
        registered = true
    }

    override fun close() {
        if (!registered) return
        registered = false
        cancelPendingLossConfirmation()
        bandwidthMeter.removeEventListener(bandwidthListener)
        runCatching {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    private fun publishNetwork(network: Network) {
        cancelPendingLossConfirmation()
        observeNetwork(network)
        publish(
            connectedProfile(
                connectivityManager.getNetworkCapabilities(network),
            ),
        )
    }

    private fun observeNetwork(network: Network) {
        if (activeNetwork != network) {
            activeNetwork = network
            measuredPlaybackBandwidthBps = null
        }
    }

    private fun publish(profile: PlaybackNetworkProfile) {
        mutableProfile.value = profile
        mutableState.value = profile.state
    }

    private fun scheduleLossConfirmation() {
        cancelPendingLossConfirmation()
        if (networkLossGraceMillis == 0L) {
            publish(currentProfile())
            return
        }
        val confirmation = Runnable {
            pendingLossConfirmation = null
            publish(currentProfile())
        }
        pendingLossConfirmation = confirmation
        handler.postDelayed(confirmation, networkLossGraceMillis)
    }

    private fun cancelPendingLossConfirmation() {
        pendingLossConfirmation?.let(handler::removeCallbacks)
        pendingLossConfirmation = null
    }

    private fun currentProfile(): PlaybackNetworkProfile {
        val network = connectivityManager.activeNetwork ?: run {
            activeNetwork = null
            measuredPlaybackBandwidthBps = null
            return PlaybackNetworkProfile.Unavailable
        }
        observeNetwork(network)
        return connectedProfile(connectivityManager.getNetworkCapabilities(network))
    }

    private fun connectedProfile(
        capabilities: NetworkCapabilities?,
    ): PlaybackNetworkProfile = PlaybackNetworkProfile(
        state = PlaybackNetworkState.AVAILABLE,
        validated = capabilities?.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
        ) == true,
        metered = capabilities?.let { value ->
            !value.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        },
        downstreamBandwidthKbps = capabilities
            ?.linkDownstreamBandwidthKbps
            ?.takeIf { it > 0 },
        upstreamBandwidthKbps = capabilities
            ?.linkUpstreamBandwidthKbps
            ?.takeIf { it > 0 },
        transports = capabilities?.playbackTransports().orEmpty(),
        measuredPlaybackBandwidthBps = measuredPlaybackBandwidthBps,
    )

    private fun NetworkCapabilities.playbackTransports(): Set<PlaybackNetworkTransport> =
        buildSet {
            if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                add(PlaybackNetworkTransport.WIFI)
            }
            if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                add(PlaybackNetworkTransport.ETHERNET)
            }
            if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                add(PlaybackNetworkTransport.CELLULAR)
            }
            if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                add(PlaybackNetworkTransport.VPN)
            }
            if (hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
                add(PlaybackNetworkTransport.BLUETOOTH)
            }
        }

    companion object {
        const val DEFAULT_NETWORK_LOSS_GRACE_MILLIS: Long = 1_500L
    }
}
