package app.ownplay.mobile.feature.live.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveRecordingPersistenceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun oldSharedPreferencesRowsLoadAndNewDiagnosticsRoundTrip() {
        val preferences = context.getSharedPreferences("ownplay_live_recordings", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val legacy = JSONObject()
            .put("id", "legacy")
            .put("source", "source")
            .put("channel", "channel")
            .put("channelName", "Channel")
            .put("title", "Program")
            .put("start", 1_000L)
            .put("end", 2_000L)
            .put("status", "SCHEDULED")
            .put("safeError", "legacy-safe-error")
        preferences.edit().putString("items", JSONArray().put(legacy).toString()).commit()

        val repository = SharedPreferencesLiveRecordingRepository(context)
        val loaded = repository.get("legacy")!!
        assertEquals(LiveRecordingStatus.SCHEDULED, loaded.status)
        assertEquals("legacy-safe-error", loaded.safeError)
        assertNull(loaded.failureReasonCode)
        assertNull(loaded.failureAtEpochMs)
        assertNull(loaded.scheduleArmedState)

        assertTrue(
            repository.put(
                loaded.copy(
                    failureReasonCode = "START_WINDOW_MISSED",
                    failureStage = "DUE_START",
                    failureAtEpochMs = 9_000L,
                    scheduleArmedState = "ARMED",
                    scheduledAtEpochMs = 8_000L,
                ),
            ),
        )
        val reloaded = SharedPreferencesLiveRecordingRepository(context).get("legacy")!!
        assertEquals("START_WINDOW_MISSED", reloaded.failureReasonCode)
        assertEquals("DUE_START", reloaded.failureStage)
        assertEquals(9_000L, reloaded.failureAtEpochMs!!)
        assertEquals("ARMED", reloaded.scheduleArmedState)
        assertEquals(8_000L, reloaded.scheduledAtEpochMs)
    }

    @Test
    fun accountCapacityMetadataContainsOnlyOpaqueAliasAndPositiveValidatedLimit() {
        val preferences = context.getSharedPreferences("ownplay_live_capacity", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val store = LiveCapacityMetadataStore(context)
        store.recordXtream("source-a", "https://service.example:8443/live", "private-user", 2)
        store.recordXtream("source-b", "https://service.example:8443/live/", "private-user", 2)

        assertEquals(store.accountKey("source-a"), store.accountKey("source-b"))
        assertEquals(2, store.maxConnections("source-a"))
        assertTrue(preferences.all.values.none { value ->
            value.toString().contains("private-user") || value.toString().contains("service.example")
        })

        store.recordXtream("source-a", "https://service.example:8443/live", "private-user", 0)
        assertNull(store.maxConnections("source-a"))
    }
}
