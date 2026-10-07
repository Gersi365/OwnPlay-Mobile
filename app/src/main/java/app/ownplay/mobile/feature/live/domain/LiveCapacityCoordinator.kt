package app.ownplay.mobile.feature.live.domain

interface LiveCapacityMetadata {
    fun accountKey(sourceId: String): String
    fun maxConnections(sourceId: String): Int?
}

enum class LiveCapacityLeaseKind { PLAYBACK, RECORDING }

data class LiveCapacityAdmission(
    val allowed: Boolean,
    val failureReasonCode: String? = null,
    val recordingIdsToFinalize: List<String> = emptyList(),
)

/** App-owned provider playback and recording transport leases. Local media has no provider lease. */
class LiveCapacityCoordinator(
    private val metadata: LiveCapacityMetadata,
) {
    private data class Lease(
        val leaseId: String,
        val sourceId: String,
        val accountKey: String,
        val channelId: String?,
        val kind: LiveCapacityLeaseKind,
        val scheduledAtEpochMs: Long,
    )

    private val lock = Any()
    private val leases = linkedMapOf<String, Lease>()

    fun acquireRecording(
        sourceId: String,
        recordingId: String,
        channelId: String,
        scheduledAtEpochMs: Long,
        sharedSessionAvailable: Boolean = false,
    ): LiveCapacityAdmission = synchronized(lock) {
        val account = metadata.accountKey(sourceId)
        val peers = leases.values.filter { it.accountKey == account && it.leaseId != recordingLeaseId(recordingId) }
        // A recording on the channel already owned by Live attaches to that DVR session.
        // It must not acquire a second provider slot or open a second provider ingress.
        val sameChannelPlaybackActive = peers.any {
            it.kind == LiveCapacityLeaseKind.PLAYBACK && it.channelId == channelId
        }
        if (sameChannelPlaybackActive) {
            return@synchronized if (sharedSessionAvailable) {
                LiveCapacityAdmission(allowed = true)
            } else {
                LiveCapacityAdmission(
                    allowed = false,
                    failureReasonCode = LiveRecordingFailureCodes.LIVE_SLOT_USED_BY_PLAYBACK,
                )
            }
        }
        val limit = metadata.maxConnections(sourceId)?.takeIf { it > 0 } ?: 1
        if (peers.size >= limit) {
            val code = if (peers.any { it.kind == LiveCapacityLeaseKind.PLAYBACK }) {
                LiveRecordingFailureCodes.LIVE_SLOT_USED_BY_PLAYBACK
            } else {
                LiveRecordingFailureCodes.LIVE_SLOT_USED_BY_RECORDING
            }
            return@synchronized LiveCapacityAdmission(false, code)
        }
        leases[recordingLeaseId(recordingId)] = Lease(
            leaseId = recordingLeaseId(recordingId),
            sourceId = sourceId,
            accountKey = account,
            channelId = channelId,
            kind = LiveCapacityLeaseKind.RECORDING,
            scheduledAtEpochMs = scheduledAtEpochMs,
        )
        LiveCapacityAdmission(allowed = true)
    }

    /**
     * User-selected playback may request recording preemption, but a recording lease remains
     * reserved until capture finalization is confirmed and the caller retries admission.
     */
    fun acquirePlayback(
        sourceId: String,
        channelId: String,
        leaseId: String = PLAYBACK_LEASE_ID,
        sharedSessionAvailable: Boolean = false,
    ): LiveCapacityAdmission =
        synchronized(lock) {
            val account = metadata.accountKey(sourceId)
            val previous = leases[leaseId]
            val peers = leases.values.filter { it.accountKey == account && it.leaseId != leaseId }
            val limit = metadata.maxConnections(sourceId)?.takeIf { it > 0 } ?: 1
            // UI playback attaches to the already-running scheduled DVR session on this exact
            // channel. The session's recording lease remains the single provider lease owner.
            if (sharedSessionAvailable && peers.any {
                    it.kind == LiveCapacityLeaseKind.RECORDING && it.channelId == channelId
                }
            ) {
                return@synchronized LiveCapacityAdmission(allowed = true)
            }
            val recordingsToFinalize = mutableListOf<String>()
            var projected = peers.size + 1
            val sameChannelRecordings = peers.filter {
                it.kind == LiveCapacityLeaseKind.RECORDING && it.channelId == channelId
            }
            sameChannelRecordings.forEach { lease ->
                recordingsToFinalize += lease.leaseId.removePrefix(RECORDING_LEASE_PREFIX)
                projected--
            }
            if (projected > limit) {
                val recordingsToPreempt = peers
                    .filter { it.kind == LiveCapacityLeaseKind.RECORDING && it !in sameChannelRecordings }
                    .sortedWith(compareByDescending<Lease> { it.scheduledAtEpochMs }.thenByDescending { it.leaseId })
                for (lease in recordingsToPreempt) {
                    if (projected <= limit) break
                    recordingsToFinalize += lease.leaseId.removePrefix(RECORDING_LEASE_PREFIX)
                    projected--
                }
            }
            if (recordingsToFinalize.isNotEmpty()) {
                return@synchronized LiveCapacityAdmission(
                    allowed = false,
                    failureReasonCode = LiveRecordingFailureCodes.LIVE_SLOT_USED_BY_RECORDING,
                    recordingIdsToFinalize = recordingsToFinalize,
                )
            }
            if (projected > limit) {
                return@synchronized LiveCapacityAdmission(
                    allowed = false,
                    failureReasonCode = LiveRecordingFailureCodes.LIVE_SLOT_USED_BY_PLAYBACK,
                )
            }
            leases[leaseId] = Lease(
                leaseId = leaseId,
                sourceId = sourceId,
                accountKey = account,
                channelId = channelId,
                kind = LiveCapacityLeaseKind.PLAYBACK,
                scheduledAtEpochMs = previous?.scheduledAtEpochMs ?: System.currentTimeMillis(),
            )
            LiveCapacityAdmission(allowed = true)
        }

    fun release(leaseId: String) = synchronized(lock) { leases.remove(leaseId); Unit }

    fun releaseRecording(recordingId: String) = release(recordingLeaseId(recordingId))

    fun activeLeaseCount(sourceId: String): Int = synchronized(lock) {
        val account = metadata.accountKey(sourceId)
        leases.values.count { it.accountKey == account }
    }

    /** Returns the single provider lease that owns an active channel session, if any. */
    fun sessionLeaseId(sourceId: String, channelId: String): String? = synchronized(lock) {
        val account = metadata.accountKey(sourceId)
        leases.values.firstOrNull {
            it.sourceId == sourceId && it.accountKey == account && it.channelId == channelId &&
                it.kind in setOf(LiveCapacityLeaseKind.PLAYBACK, LiveCapacityLeaseKind.RECORDING)
        }?.leaseId
    }

    private fun recordingLeaseId(recordingId: String) = RECORDING_LEASE_PREFIX + recordingId

    companion object {
        const val PLAYBACK_LEASE_ID = "ownplay-live-playback"
        private const val RECORDING_LEASE_PREFIX = "recording:"
    }
}
