package app.ownplay.mobile.feature.live.data

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveRecordingFailurePolicyTest {
    @Test
    fun typedStorageFailureKeepsStableCategoryAndSafeText() {
        val failure = LiveRecordingFailurePolicy.classify(
            LiveRecordingCaptureFailureException(
                LiveRecordingFailureCategory.STORAGE,
                "OwnPlay could not finalize the recording file.",
            ),
        )
        assertEquals(LiveRecordingFailureCategory.STORAGE, failure.category)
        assertEquals("OwnPlay could not finalize the recording file.", failure.safeMessage)
    }

    @Test
    fun authorizationAndTimeoutAreSeparated() {
        assertEquals(
            LiveRecordingFailureCategory.AUTHORIZATION,
            LiveRecordingFailurePolicy.classify(SecurityException("secret details")).category,
        )
        assertEquals(
            LiveRecordingFailureCategory.NETWORK,
            LiveRecordingFailurePolicy.classify(SocketTimeoutException("provider URL")).category,
        )
    }

    @Test
    fun genericIoNeverPersistsRawExceptionMessage() {
        val failure = LiveRecordingFailurePolicy.classify(IOException("https://user:pass@example.invalid/live"))
        assertEquals(LiveRecordingFailureCategory.NETWORK, failure.category)
        assertFalse(failure.safeMessage.contains("example.invalid"))
        assertFalse(failure.safeMessage.contains("pass"))
    }
}
