package app.ownplay.mobile.feature.live.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCapacityCoordinatorTest {
    private class Metadata(
        private val aliases: Map<String, String> = emptyMap(),
        private val limits: Map<String, Int?> = emptyMap(),
    ) : LiveCapacityMetadata {
        override fun accountKey(sourceId: String): String = aliases[sourceId] ?: "source:$sourceId"
        override fun maxConnections(sourceId: String): Int? = limits[sourceId]
    }

    @Test
    fun unknownOrInvalidCapacityConservativelyAllowsOneLease() {
        listOf(null, 0, -2).forEach { configuredLimit ->
            val coordinator = LiveCapacityCoordinator(
                Metadata(limits = mapOf("source" to configuredLimit)),
            )
            assertTrue(coordinator.acquirePlayback("source", "channel-a").allowed)
            val denied = coordinator.acquireRecording("source", "recording", "channel-b", 1L)
            assertFalse(denied.allowed)
            assertEquals(LiveRecordingFailureCodes.LIVE_SLOT_USED_BY_PLAYBACK, denied.failureReasonCode)
        }
    }

    @Test
    fun positiveValidatedCapacityPermitsIndependentSecondLiveLease() {
        val coordinator = LiveCapacityCoordinator(Metadata(limits = mapOf("source" to 2)))
        assertTrue(coordinator.acquirePlayback("source", "watch").allowed)
        assertTrue(coordinator.acquireRecording("source", "record", "other", 2L).allowed)
        assertEquals(2, coordinator.activeLeaseCount("source"))
    }

    @Test
    fun sameChannelRecordingIsDeniedWithoutAProvenSharedTransport() {
        val coordinator = LiveCapacityCoordinator(Metadata(limits = mapOf("source" to 4)))
        coordinator.acquirePlayback("source", "same-channel")

        val denied = coordinator.acquireRecording("source", "record", "same-channel", 2L)

        assertFalse(denied.allowed)
        assertEquals(LiveRecordingFailureCodes.LIVE_SLOT_USED_BY_PLAYBACK, denied.failureReasonCode)
    }

    @Test
    fun playbackRequestsSameChannelFinalizationBeforeTakingTheProviderLease() {
        val coordinator = LiveCapacityCoordinator(Metadata(limits = mapOf("source" to 3)))
        coordinator.acquireRecording("source", "record", "same-channel", 2L)

        val playback = coordinator.acquirePlayback("source", "same-channel")

        assertFalse(playback.allowed)
        assertEquals(listOf("record"), playback.recordingIdsToFinalize)
        assertEquals(1, coordinator.activeLeaseCount("source"))

        coordinator.releaseRecording("record")
        assertTrue(coordinator.acquirePlayback("source", "same-channel").allowed)
        assertEquals(1, coordinator.activeLeaseCount("source"))
    }

    @Test
    fun playbackKeepsTheSelectedRecordingLeaseUntilFinalizationWhenCapacityRequiresIt() {
        val coordinator = LiveCapacityCoordinator(Metadata(limits = mapOf("source" to 2)))
        assertTrue(coordinator.acquireRecording("source", "record-early", "a", 10L).allowed)
        assertTrue(coordinator.acquireRecording("source", "record-late", "b", 20L).allowed)

        val playback = coordinator.acquirePlayback("source", "watch")

        assertFalse(playback.allowed)
        assertEquals(listOf("record-late"), playback.recordingIdsToFinalize)
        assertEquals(2, coordinator.activeLeaseCount("source"))
        coordinator.releaseRecording("record-late")
        assertTrue(coordinator.acquirePlayback("source", "watch").allowed)
        assertEquals(2, coordinator.activeLeaseCount("source"))

        val spareCapacity = LiveCapacityCoordinator(Metadata(limits = mapOf("source" to 2)))
        assertTrue(spareCapacity.acquireRecording("source", "keep-recording", "a", 10L).allowed)
        assertTrue(spareCapacity.acquirePlayback("source", "watch").recordingIdsToFinalize.isEmpty())
    }

    @Test
    fun aliasesShareCapacityUsingOpaqueAccountScope() {
        val coordinator = LiveCapacityCoordinator(
            Metadata(
                aliases = mapOf("alias-one" to "opaque-account", "alias-two" to "opaque-account"),
                limits = mapOf("alias-one" to 1, "alias-two" to 1),
            ),
        )
        coordinator.acquirePlayback("alias-one", "channel")

        val admission = coordinator.acquireRecording("alias-two", "record", "other-channel", 5L)

        assertFalse(admission.allowed)
        assertEquals(LiveRecordingFailureCodes.LIVE_SLOT_USED_BY_PLAYBACK, admission.failureReasonCode)
    }
}
