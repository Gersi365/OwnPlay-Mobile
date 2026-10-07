package app.ownplay.mobile.feature.live.data

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ForegroundServiceStartNotAllowedException
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import app.ownplay.mobile.MainActivity
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.R
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.downloads.data.DownloadNotificationNavigationContract
import app.ownplay.mobile.downloads.data.DownloadStorage
import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingRepository
import app.ownplay.mobile.feature.live.domain.LiveRecordingRemovalPolicy
import app.ownplay.mobile.feature.live.domain.LiveRecordingFinalizationState
import app.ownplay.mobile.feature.live.domain.LiveRecordingStopPolicy
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import app.ownplay.mobile.feature.live.domain.LiveRecordingTransitionPolicy
import app.ownplay.mobile.feature.live.domain.LiveRecordingFailureCodes
import app.ownplay.mobile.feature.live.domain.LiveRecordingFailureStages
import app.ownplay.mobile.feature.live.domain.LiveRecordingScheduleArmedStates
import app.ownplay.mobile.feature.live.domain.LiveCapacityCoordinator
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
    "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

class SharedPreferencesLiveRecordingRepository(context: Context) : LiveRecordingRepository {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val mutableRecordings = MutableStateFlow(readAll())
    override val recordings: StateFlow<List<LiveRecording>> = mutableRecordings.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == RECORDINGS_KEY) mutableRecordings.value = readAll()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun get(recordingId: String): LiveRecording? =
        recordings.value.firstOrNull { it.recordingId == recordingId }

    override fun put(recording: LiveRecording): Boolean = synchronized(lock) {
        val next = recordings.value.filterNot { it.recordingId == recording.recordingId } + recording
        val encoded = JSONArray().apply { next.forEach { put(it.toJson()) } }.toString()
        val saved = preferences.edit().putString(RECORDINGS_KEY, encoded).commit()
        if (saved) mutableRecordings.value = next.sortedBy(LiveRecording::startEpochSeconds)
        saved
    }

    override fun update(recordingId: String, transform: (LiveRecording) -> LiveRecording): Boolean = synchronized(lock) {
        val current = get(recordingId) ?: return@synchronized false
        val next = transform(current)
        if (next == current) true else put(next)
    }

    override fun remove(recordingId: String): Boolean = synchronized(lock) {
        val next = recordings.value.filterNot { it.recordingId == recordingId }
        if (next.size == recordings.value.size) return@synchronized true
        val encoded = JSONArray().apply { next.forEach { put(it.toJson()) } }.toString()
        val saved = preferences.edit().putString(RECORDINGS_KEY, encoded).commit()
        if (saved) mutableRecordings.value = next.sortedBy(LiveRecording::startEpochSeconds)
        saved
    }

    private fun readAll(): List<LiveRecording> {
        val raw = preferences.getString(RECORDINGS_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    runCatching { item.toRecording() }.getOrNull()?.let(::add)
                }
            }.sortedBy(LiveRecording::startEpochSeconds)
        }.getOrDefault(emptyList())
    }

    private fun LiveRecording.toJson(): JSONObject = JSONObject()
        .put("id", recordingId)
        .put("source", sourceId)
        .put("channel", channelId)
        .put("channelName", channelName)
        .put("title", title)
        .put("start", startEpochSeconds)
        .put("end", endEpochSeconds)
        .put("status", status.name)
        .put("localReference", localReference)
        .put("safeError", safeError)
        .put("stopPolicy", stopPolicy.name)
        .put("deadline", deadlineEpochSeconds)
        .put("actualStart", actualStartEpochSeconds)
        .put("captureRequested", captureRequestedEpochSeconds)
        .put("pendingOutput", pendingOutputDescriptor)
        .put("progressBytes", progressBytes)
        .put("finalization", finalizationState.name)
        .put("partialReference", partialLocalReference)
        .put("failureReason", failureReason)
        .put("failureReasonCode", failureReasonCode)
        .put("failureStage", failureStage)
        .put("failureAtEpochMs", failureAtEpochMs)
        .put("scheduleArmedState", scheduleArmedState)
        .put("scheduledAtEpochMs", scheduledAtEpochMs)

    private fun JSONObject.toRecording(): LiveRecording = LiveRecording(
        recordingId = getString("id"),
        sourceId = getString("source"),
        channelId = getString("channel"),
        channelName = getString("channelName"),
        title = getString("title"),
        startEpochSeconds = getLong("start"),
        endEpochSeconds = getLong("end"),
        status = LiveRecordingStatus.valueOf(getString("status")),
        localReference = optString("localReference").takeIf(String::isNotBlank),
        safeError = optString("safeError").takeIf(String::isNotBlank),
        stopPolicy = runCatching { LiveRecordingStopPolicy.valueOf(optString("stopPolicy")) }
            .getOrDefault(LiveRecordingStopPolicy.PROGRAM_END),
        deadlineEpochSeconds = optLong("deadline", getLong("end")),
        actualStartEpochSeconds = optLong("actualStart").takeIf { it > 0L },
        captureRequestedEpochSeconds = optLong("captureRequested").takeIf { it > 0L },
        pendingOutputDescriptor = optString("pendingOutput").takeIf { it.isNotBlank() && it != "null" },
        progressBytes = optLong("progressBytes").coerceAtLeast(0L),
        finalizationState = runCatching {
            LiveRecordingFinalizationState.valueOf(optString("finalization"))
        }.getOrDefault(LiveRecordingFinalizationState.NOT_STARTED),
        partialLocalReference = optString("partialReference").takeIf(String::isNotBlank),
        failureReason = optString("failureReason").takeIf(String::isNotBlank),
        failureReasonCode = optString("failureReasonCode").takeIf(String::isNotBlank),
        failureStage = optString("failureStage").takeIf(String::isNotBlank),
        failureAtEpochMs = optLong("failureAtEpochMs").takeIf { it > 0L },
        scheduleArmedState = optString("scheduleArmedState").takeIf(String::isNotBlank),
        scheduledAtEpochMs = optLong("scheduledAtEpochMs").takeIf { it > 0L },
    )

    companion object {
        private const val PREFERENCES_NAME = "ownplay_live_recordings"
        private const val RECORDINGS_KEY = "items"
    }
}

enum class LiveRecordingScheduleResult {
    SCHEDULED,
    ALREADY_EXISTS,
    EXACT_ALARM_ACCESS_REQUIRED,
    SOURCE_UNAVAILABLE,
    FAILED,
}

internal object LiveRecordingScheduleStatePolicy {
    const val EXACT_ALARM_ACCESS_ERROR =
        "Exact-alarm access is required before this scheduled recording can start."

    fun waitingForExactAlarmAccess(recording: LiveRecording, permissionWasRevoked: Boolean = false): LiveRecording {
        val reasonCode = if (permissionWasRevoked) {
            LiveRecordingFailureCodes.EXACT_ALARM_PERMISSION_REVOKED
        } else {
            LiveRecordingFailureCodes.EXACT_ALARM_PERMISSION_MISSING
        }
        return recording.copy(
            status = LiveRecordingStatus.SCHEDULED,
            safeError = EXACT_ALARM_ACCESS_ERROR,
            failureReason = reasonCode,
            failureReasonCode = reasonCode,
            failureStage = LiveRecordingFailureStages.ALARM_ARMING,
            failureAtEpochMs = System.currentTimeMillis(),
            scheduleArmedState = LiveRecordingScheduleArmedStates.NOT_ARMED_PERMISSION,
        )
    }

    fun armed(recording: LiveRecording): LiveRecording =
        recording.copy(
            safeError = recording.safeError.takeUnless { it == EXACT_ALARM_ACCESS_ERROR },
            failureReasonCode = recording.failureReasonCode.takeUnless {
                it == LiveRecordingFailureCodes.EXACT_ALARM_PERMISSION_MISSING ||
                    it == LiveRecordingFailureCodes.EXACT_ALARM_PERMISSION_REVOKED ||
                    it == LiveRecordingFailureCodes.EXACT_ALARM_SCHEDULE_ERROR
            },
            failureReason = recording.failureReason.takeUnless {
                it == LiveRecordingFailureCodes.EXACT_ALARM_PERMISSION_MISSING ||
                    it == LiveRecordingFailureCodes.EXACT_ALARM_PERMISSION_REVOKED ||
                    it == LiveRecordingFailureCodes.EXACT_ALARM_SCHEDULE_ERROR
            },
            failureStage = recording.failureStage.takeUnless { it == LiveRecordingFailureStages.ALARM_ARMING },
            failureAtEpochMs = recording.failureAtEpochMs.takeUnless {
                recording.failureReasonCode == LiveRecordingFailureCodes.EXACT_ALARM_PERMISSION_MISSING ||
                    recording.failureReasonCode == LiveRecordingFailureCodes.EXACT_ALARM_PERMISSION_REVOKED ||
                    recording.failureReasonCode == LiveRecordingFailureCodes.EXACT_ALARM_SCHEDULE_ERROR
            },
            scheduleArmedState = LiveRecordingScheduleArmedStates.ARMED,
        )
}

internal sealed interface LiveRecordingAlarmRecoveryDecision {
    data object Missed : LiveRecordingAlarmRecoveryDecision
    data object PermissionRequired : LiveRecordingAlarmRecoveryDecision
    data class ScheduleAt(val epochSeconds: Long) : LiveRecordingAlarmRecoveryDecision
}

internal object LiveRecordingAlarmRecoveryPolicy {
    fun decide(
        recording: LiveRecording,
        nowEpochSeconds: Long,
        exactAlarmAccess: Boolean,
    ): LiveRecordingAlarmRecoveryDecision = when {
        recording.endEpochSeconds <= nowEpochSeconds -> LiveRecordingAlarmRecoveryDecision.Missed
        !exactAlarmAccess -> LiveRecordingAlarmRecoveryDecision.PermissionRequired
        else -> LiveRecordingAlarmRecoveryDecision.ScheduleAt(
            recording.startEpochSeconds.coerceAtLeast(nowEpochSeconds),
        )
    }
}

internal object LiveRecordingDueOrderPolicy {
    fun firstDue(recordings: List<LiveRecording>, nowEpochMs: Long): LiveRecording? =
        recordings.asSequence()
            .filter {
                it.status == LiveRecordingStatus.SCHEDULED &&
                    it.startEpochSeconds * 1_000L <= nowEpochMs &&
                    it.endEpochSeconds * 1_000L > nowEpochMs
            }
            .minWithOrNull(
                compareBy<LiveRecording> { it.startEpochSeconds }
                    .thenBy { it.scheduledAtEpochMs ?: (it.startEpochSeconds * 1_000L) }
                    .thenBy { it.recordingId },
            )
}

class AndroidLiveRecordingScheduler(
    context: Context,
    private val repository: LiveRecordingRepository,
    private val liveCapacityCoordinator: LiveCapacityCoordinator? = null,
) {
    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)
    private val scheduleLock = Any()
    private val sourceRemovalBlockCounts = mutableMapOf<String, Int>()

    fun blockSourceSchedules(sourceId: SourceId) {
        synchronized(scheduleLock) {
            sourceRemovalBlockCounts[sourceId.value] =
                (sourceRemovalBlockCounts[sourceId.value] ?: 0) + 1
        }
    }

    fun allowSourceSchedules(sourceId: SourceId) {
        synchronized(scheduleLock) {
            val current = sourceRemovalBlockCounts[sourceId.value] ?: return@synchronized
            if (current <= 1) sourceRemovalBlockCounts.remove(sourceId.value)
            else sourceRemovalBlockCounts[sourceId.value] = current - 1
        }
    }

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun schedule(recording: LiveRecording): LiveRecordingScheduleResult = synchronized(scheduleLock) {
        if (sourceRemovalBlockCounts.containsKey(recording.sourceId)) {
            return@synchronized LiveRecordingScheduleResult.SOURCE_UNAVAILABLE
        }
        scheduleLocked(recording)
    }

    private fun scheduleLocked(recording: LiveRecording): LiveRecordingScheduleResult {
        val existing = repository.get(recording.recordingId)
        if (
            existing != null && existing.status in setOf(
                LiveRecordingStatus.STARTING,
                LiveRecordingStatus.RECORDING,
                LiveRecordingStatus.FINALIZING,
                LiveRecordingStatus.COMPLETED,
                LiveRecordingStatus.PARTIAL,
            )
        ) {
            return LiveRecordingScheduleResult.ALREADY_EXISTS
        }
        val scheduled = when {
            existing != null && existing.status == LiveRecordingStatus.SCHEDULED ->
                existing.copy(scheduledAtEpochMs = existing.scheduledAtEpochMs ?: System.currentTimeMillis())
            existing != null -> {
                recording.copy(
                    status = LiveRecordingStatus.SCHEDULED,
                    scheduledAtEpochMs = System.currentTimeMillis(),
                    failureReason = null,
                    failureReasonCode = null,
                    failureStage = null,
                    failureAtEpochMs = null,
                    scheduleArmedState = null,
                    safeError = null,
                ).also { retry ->
                    if (!repository.put(retry)) return LiveRecordingScheduleResult.FAILED
                }
            }
            else -> {
                recording.copy(
                    status = LiveRecordingStatus.SCHEDULED,
                    scheduledAtEpochMs = recording.scheduledAtEpochMs ?: System.currentTimeMillis(),
                ).also { fresh ->
                    if (!repository.put(fresh)) return LiveRecordingScheduleResult.FAILED
                }
            }
        }
        if (!canScheduleExactAlarms()) {
            if (!repository.put(LiveRecordingScheduleStatePolicy.waitingForExactAlarmAccess(scheduled))) {
                return LiveRecordingScheduleResult.FAILED
            }
            return LiveRecordingScheduleResult.EXACT_ALARM_ACCESS_REQUIRED
        }
        return try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                scheduled.startEpochSeconds * 1_000L,
                alarmIntent(scheduled.recordingId),
            )
            val armed = LiveRecordingScheduleStatePolicy.armed(scheduled)
            if (armed != scheduled && !repository.put(armed)) {
                return LiveRecordingScheduleResult.FAILED
            }
            LiveRecordingScheduleResult.SCHEDULED
        } catch (_: SecurityException) {
            repository.put(
                LiveRecordingScheduleStatePolicy.waitingForExactAlarmAccess(
                    scheduled,
                    permissionWasRevoked = scheduled.scheduleArmedState == LiveRecordingScheduleArmedStates.ARMED,
                ),
            )
            LiveRecordingScheduleResult.EXACT_ALARM_ACCESS_REQUIRED
        } catch (_: Exception) {
                repository.put(
                    scheduled.copy(
                    status = LiveRecordingStatus.SCHEDULED,
                    safeError = SAFE_SCHEDULE_ERROR,
                    failureReason = LiveRecordingFailureCodes.EXACT_ALARM_SCHEDULE_ERROR,
                    failureReasonCode = LiveRecordingFailureCodes.EXACT_ALARM_SCHEDULE_ERROR,
                    failureStage = LiveRecordingFailureStages.ALARM_ARMING,
                    failureAtEpochMs = System.currentTimeMillis(),
                    scheduleArmedState = LiveRecordingScheduleArmedStates.NOT_ARMED_ERROR,
                ),
            )
            LiveRecordingScheduleResult.FAILED
        }
    }

    fun startNow(recording: LiveRecording): Boolean = synchronized(scheduleLock) {
        if (sourceRemovalBlockCounts.containsKey(recording.sourceId)) return@synchronized false
        startNowLocked(recording)
    }

    private fun startNowLocked(recording: LiveRecording): Boolean {
        val existing = repository.get(recording.recordingId)
        if (existing?.status in setOf(LiveRecordingStatus.STARTING, LiveRecordingStatus.RECORDING, LiveRecordingStatus.FINALIZING)) return true
        if (
            existing != null && existing.status !in setOf(
                LiveRecordingStatus.SCHEDULED,
                LiveRecordingStatus.FAILED,
                LiveRecordingStatus.CANCELLED,
            )
        ) return false
        if (
            (existing == null || existing.status != LiveRecordingStatus.SCHEDULED) &&
            !repository.put(recording.copy(
                status = LiveRecordingStatus.SCHEDULED,
                scheduledAtEpochMs = recording.scheduledAtEpochMs ?: System.currentTimeMillis(),
            ))
        ) {
            return false
        }
        val current = repository.get(recording.recordingId) ?: recording
        if (!reserveRecording(current)) return false
        alarmManager.cancel(alarmIntent(recording.recordingId))
        return runCatching {
            applicationContext.startForegroundService(serviceIntent(recording.recordingId))
            true
        }.getOrElse {
            liveCapacityCoordinator?.releaseRecording(recording.recordingId)
            repository.put(
                current.copy(
                    status = LiveRecordingStatus.FAILED,
                    safeError = safeStartMessage(it),
                    failureReasonCode = startFailureCode(it),
                    failureStage = LiveRecordingFailureStages.FOREGROUND_SERVICE,
                    failureAtEpochMs = System.currentTimeMillis(),
                ),
            )
            false
        }
    }

    fun cancel(recordingId: String): Boolean {
        alarmManager.cancel(alarmIntent(recordingId))
        val recording = repository.get(recordingId) ?: return true
        if (recording.status == LiveRecordingStatus.DELETE_PENDING) return false
        if (recording.status in setOf(LiveRecordingStatus.STARTING, LiveRecordingStatus.RECORDING)) {
            return runCatching {
            applicationContext.startService(
                    serviceIntent(recordingId).setAction(LiveRecordingService.ACTION_STOP_SAVE),
                )
                true
            }.getOrDefault(false)
        }
        if (recording.status == LiveRecordingStatus.FINALIZING) return true
        return repository.put(recording.copy(status = LiveRecordingStatus.CANCELLED))
    }

    fun restoreScheduledAlarms() {
        synchronized(scheduleLock) {
            val now = System.currentTimeMillis() / 1_000L
            val hasExactAlarmAccess = canScheduleExactAlarms()
            repository.recordings.value
                .filter {
                    it.status == LiveRecordingStatus.SCHEDULED &&
                        !sourceRemovalBlockCounts.containsKey(it.sourceId)
                }
                .forEach { recording ->
                    when (
                        val decision = LiveRecordingAlarmRecoveryPolicy.decide(
                            recording = recording,
                            nowEpochSeconds = now,
                            exactAlarmAccess = hasExactAlarmAccess,
                        )
                    ) {
                        LiveRecordingAlarmRecoveryDecision.Missed -> repository.put(
                            recording.copy(
                                status = LiveRecordingStatus.FAILED,
                                safeError = SAFE_MISSED_ERROR,
                                failureReasonCode = LiveRecordingFailureCodes.START_WINDOW_MISSED,
                                failureStage = LiveRecordingFailureStages.DUE_START,
                                failureAtEpochMs = System.currentTimeMillis(),
                            ),
                        )
                        LiveRecordingAlarmRecoveryDecision.PermissionRequired -> repository.put(
                            LiveRecordingScheduleStatePolicy.waitingForExactAlarmAccess(
                                recording,
                                permissionWasRevoked = recording.scheduleArmedState == LiveRecordingScheduleArmedStates.ARMED,
                            ),
                        )
                        is LiveRecordingAlarmRecoveryDecision.ScheduleAt -> try {
                            alarmManager.setExactAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                decision.epochSeconds * 1_000L,
                                alarmIntent(recording.recordingId),
                            )
                            val armed = LiveRecordingScheduleStatePolicy.armed(recording)
                            if (armed != recording) {
                                repository.put(armed)
                            }
                        } catch (_: SecurityException) {
                            repository.put(
                                LiveRecordingScheduleStatePolicy.waitingForExactAlarmAccess(
                                    recording,
                                    permissionWasRevoked = recording.scheduleArmedState == LiveRecordingScheduleArmedStates.ARMED,
                                ),
                            )
                        } catch (_: Exception) {
                            repository.put(
                                recording.copy(
                                    status = LiveRecordingStatus.SCHEDULED,
                                    safeError = SAFE_SCHEDULE_ERROR,
                                    failureReason = LiveRecordingFailureCodes.EXACT_ALARM_SCHEDULE_ERROR,
                                    failureReasonCode = LiveRecordingFailureCodes.EXACT_ALARM_SCHEDULE_ERROR,
                                    failureStage = LiveRecordingFailureStages.ALARM_ARMING,
                                    failureAtEpochMs = System.currentTimeMillis(),
                                    scheduleArmedState = LiveRecordingScheduleArmedStates.NOT_ARMED_ERROR,
                                ),
                            )
                        }
                    }
                }
        }
    }

    internal fun startScheduled(recordingId: String): Boolean = synchronized(scheduleLock) {
        val requested = repository.get(recordingId) ?: return@synchronized false
        if (requested.status != LiveRecordingStatus.SCHEDULED) return@synchronized false
        val nowMs = System.currentTimeMillis()
        if (requested.endEpochSeconds * 1_000L <= nowMs) {
            fail(requested, LiveRecordingFailureCodes.START_WINDOW_MISSED, LiveRecordingFailureStages.DUE_START, SAFE_MISSED_ERROR)
            return@synchronized false
        }
        val winner = LiveRecordingDueOrderPolicy.firstDue(repository.recordings.value, nowMs)
        if (winner != null && winner.recordingId != recordingId) {
            fail(
                requested,
                LiveRecordingFailureCodes.APP_RECORDING_SERVICE_BUSY,
                LiveRecordingFailureStages.DUE_START,
                "Another due recording has priority in OwnPlay's single-recording service.",
            )
            return@synchronized false
        }
        if (!reserveRecording(requested)) return@synchronized false
        try {
            applicationContext.startForegroundService(serviceIntent(recordingId))
            true
        } catch (error: Exception) {
            liveCapacityCoordinator?.releaseRecording(recordingId)
            fail(requested, startFailureCode(error), LiveRecordingFailureStages.FOREGROUND_SERVICE, safeStartMessage(error))
            false
        }
    }

    private fun reserveRecording(recording: LiveRecording): Boolean {
        val admission = liveCapacityCoordinator?.acquireRecording(
            sourceId = recording.sourceId,
            recordingId = recording.recordingId,
            channelId = recording.channelId,
            scheduledAtEpochMs = recording.scheduledAtEpochMs ?: System.currentTimeMillis(),
        ) ?: return true
        if (admission.allowed) return true
        val code = admission.failureReasonCode ?: LiveRecordingFailureCodes.LIVE_SLOT_USED_BY_RECORDING
        val message = when (code) {
            LiveRecordingFailureCodes.LIVE_SLOT_USED_BY_PLAYBACK ->
                "A provider stream is using the available connection. Playback continues; recording was not started."
            else -> "Another recording is using the available Live connection."
        }
        fail(recording, code, LiveRecordingFailureStages.CAPACITY_ADMISSION, message)
        return false
    }

    fun preemptForPlayback(recordingId: String): Boolean = runCatching {
        val component = applicationContext.startService(
            serviceIntent(recordingId).setAction(LiveRecordingService.ACTION_PREEMPT_FOR_PLAYBACK),
        )
        component != null
    }.getOrDefault(false)

    suspend fun preemptForPlaybackAndAwait(recordingIds: List<String>): Boolean {
        if (recordingIds.isEmpty()) return true
        for (recordingId in recordingIds) {
            val recording = repository.get(recordingId) ?: continue
            when (recording.status) {
                LiveRecordingStatus.SCHEDULED -> {
                    alarmManager.cancel(alarmIntent(recordingId))
                    repository.update(recordingId) { current ->
                        if (current.status != LiveRecordingStatus.SCHEDULED) current else current.copy(
                            status = LiveRecordingStatus.CANCELLED,
                            safeError = "Scheduled recording was cancelled because Live playback took priority.",
                            failureReason = LiveRecordingFailureCodes.PLAYBACK_PREEMPTED_RECORDING,
                            failureReasonCode = LiveRecordingFailureCodes.PLAYBACK_PREEMPTED_RECORDING,
                            failureStage = LiveRecordingFailureStages.PLAYBACK_PREEMPTION,
                            failureAtEpochMs = System.currentTimeMillis(),
                        )
                    }
                    if (repository.get(recordingId)?.status == LiveRecordingStatus.CANCELLED) {
                        liveCapacityCoordinator?.releaseRecording(recordingId)
                    }
                    if (
                        repository.get(recordingId)?.status in setOf(
                            LiveRecordingStatus.STARTING,
                            LiveRecordingStatus.RECORDING,
                        )
                    ) {
                        if (!preemptForPlayback(recordingId)) return false
                    }
                }
                LiveRecordingStatus.STARTING,
                LiveRecordingStatus.RECORDING,
                -> if (!preemptForPlayback(recordingId)) return false
                LiveRecordingStatus.FINALIZING,
                LiveRecordingStatus.COMPLETED,
                LiveRecordingStatus.PARTIAL,
                LiveRecordingStatus.FAILED,
                LiveRecordingStatus.CANCELLED,
                LiveRecordingStatus.DELETE_PENDING,
                -> Unit
            }
        }
        val finalized = withTimeoutOrNull(PREEMPTION_FINALIZATION_TIMEOUT_MS) {
            repository.recordings.first { recordings ->
                recordingIds.all { recordingId ->
                    val status = recordings.firstOrNull { it.recordingId == recordingId }?.status
                    status == null || status in setOf(
                        LiveRecordingStatus.COMPLETED,
                        LiveRecordingStatus.PARTIAL,
                        LiveRecordingStatus.FAILED,
                        LiveRecordingStatus.CANCELLED,
                        LiveRecordingStatus.DELETE_PENDING,
                    )
                }
            }
            true
        } ?: false
        if (finalized) recordingIds.forEach { liveCapacityCoordinator?.releaseRecording(it) }
        return finalized
    }

    private fun fail(recording: LiveRecording, code: String, stage: String, message: String) {
        repository.put(
            recording.copy(
                status = LiveRecordingStatus.FAILED,
                safeError = message,
                failureReason = recording.failureReason ?: code,
                failureReasonCode = code,
                failureStage = stage,
                failureAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun startFailureCode(error: Throwable): String = when {
        error is SecurityException -> LiveRecordingFailureCodes.ANDROID_BACKGROUND_START_DENIED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error is ForegroundServiceStartNotAllowedException ->
            LiveRecordingFailureCodes.ANDROID_BACKGROUND_START_DENIED
        else -> LiveRecordingFailureCodes.UNKNOWN
    }

    private fun safeStartMessage(error: Throwable): String =
        if (startFailureCode(error) == LiveRecordingFailureCodes.ANDROID_BACKGROUND_START_DENIED) {
            "Android did not allow OwnPlay to start the recording service in the background."
        } else {
            SAFE_START_ERROR
        }

    private fun serviceIntent(recordingId: String): Intent =
        Intent(applicationContext, LiveRecordingService::class.java)
            .putExtra(EXTRA_RECORDING_ID, recordingId)

    private fun alarmIntent(recordingId: String): PendingIntent {
        val intent = Intent(applicationContext, LiveRecordingAlarmReceiver::class.java)
            .setAction(ACTION_START_RECORDING)
            .setData(Uri.parse("ownplay://recording/" + Uri.encode(recordingId)))
            .putExtra(EXTRA_RECORDING_ID, recordingId)
        return PendingIntent.getBroadcast(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val EXTRA_RECORDING_ID = "recording_id"
        const val ACTION_START_RECORDING = "app.ownplay.mobile.action.START_LIVE_RECORDING"
        private const val PREEMPTION_FINALIZATION_TIMEOUT_MS = 30_000L
        private const val SAFE_SCHEDULE_ERROR = "Android could not schedule this recording."
        private const val SAFE_START_ERROR = "Android could not start this recording."
        private const val SAFE_MISSED_ERROR = "The scheduled recording window has passed."
    }
}

internal class LiveRecordingRemovalManager(
    private val repository: LiveRecordingRepository,
    private val cancelScheduled: (String) -> Boolean,
    private val storage: DownloadStorage,
) {
    suspend fun recoverPendingDeletes() {
        for (recording in repository.recordings.value.filter { it.status == LiveRecordingStatus.DELETE_PENDING }) {
            retryPendingDelete(recording.recordingId)
        }
    }

    suspend fun retryPendingDelete(recordingId: String): Boolean {
        val current = repository.get(recordingId) ?: return true
        if (current.status != LiveRecordingStatus.DELETE_PENDING) return false
        val reference = current.localReference
        val fileRemoved = try {
            reference == null || storage.removePublished(reference)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (fileRemoved) return runCatching { repository.remove(current.recordingId) }.getOrDefault(false)
        repository.put(current.copy(safeError = "Local file removal is still pending. Retry when storage is available."))
        return false
    }

    suspend fun remove(
        recordingId: String,
        deleteLocalFile: Boolean,
    ): Boolean {
        val recording = repository.get(recordingId) ?: return true
        if (!LiveRecordingRemovalPolicy.canRemove(recording.status)) return false

        if (
            recording.status == LiveRecordingStatus.SCHEDULED &&
            !cancelScheduled(recordingId)
        ) {
            return false
        }

        val current = repository.get(recordingId) ?: return true
        if (deleteLocalFile) {
            if (!LiveRecordingRemovalPolicy.canDeleteLocalFile(current)) return false
            val reference = current.localReference ?: return false
            if (!repository.put(current.copy(status = LiveRecordingStatus.DELETE_PENDING, safeError = "Removing local file…"))) {
                return false
            }
            val removed = try {
                storage.removePublished(reference)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!removed) {
                repository.put(current.copy(
                    status = LiveRecordingStatus.DELETE_PENDING,
                    safeError = "Local file removal is still pending. Retry when storage is available.",
                ))
                return false
            }
            return runCatching { repository.remove(recordingId) }.getOrDefault(false)
        }

        return repository.remove(recordingId)
    }
}

internal class RecordingAwareSourceRepository(
    private val delegate: SourceRepository,
    private val sourceDao: SourceDao,
    private val recordings: LiveRecordingRepository,
    private val scheduler: AndroidLiveRecordingScheduler,
    private val liveCapacityMetadata: LiveCapacityMetadataStore? = null,
) : SourceRepository by delegate {
    override suspend fun removeSource(sourceId: SourceId): Boolean {
        scheduler.blockSourceSchedules(sourceId)
        var captured = emptyList<LiveRecording>()
        return try {
            captured = recordings.recordings.value.filter { it.sourceId == sourceId.value }
            if (!quiesce(captured)) {
                withContext(NonCancellable) {
                    scheduler.allowSourceSchedules(sourceId)
                    restoreScheduled(captured)
                }
                return false
            }
            if (!delegate.removeSource(sourceId)) {
                withContext(NonCancellable) {
                    scheduler.allowSourceSchedules(sourceId)
                    restoreScheduled(captured)
                }
                return false
            }
            liveCapacityMetadata?.clear(sourceId.value)
            return true
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (sourceStillExists(sourceId)) {
                    scheduler.allowSourceSchedules(sourceId)
                    restoreScheduled(captured)
                }
            }
            throw cancelled
        } catch (_: Exception) {
            val sourceWasRemoved = withContext(NonCancellable) {
                if (sourceStillExists(sourceId)) {
                    scheduler.allowSourceSchedules(sourceId)
                    restoreScheduled(captured)
                    false
                } else {
                    true
                }
            }
            sourceWasRemoved
        }
    }

    private suspend fun quiesce(captured: List<LiveRecording>): Boolean {
        for (recording in captured) {
            when (recording.status) {
                LiveRecordingStatus.SCHEDULED -> if (!scheduler.cancel(recording.recordingId)) return false
                LiveRecordingStatus.STARTING,
                LiveRecordingStatus.RECORDING,
                -> {
                    if (!scheduler.cancel(recording.recordingId)) return false
                    if (!awaitRecordingIdle(recording.recordingId)) return false
                }
                LiveRecordingStatus.FINALIZING -> if (!awaitRecordingIdle(recording.recordingId)) return false
                LiveRecordingStatus.COMPLETED,
                LiveRecordingStatus.PARTIAL,
                LiveRecordingStatus.FAILED,
                LiveRecordingStatus.CANCELLED,
                LiveRecordingStatus.DELETE_PENDING,
                -> Unit
            }
        }
        return true
    }

    private suspend fun awaitRecordingIdle(recordingId: String): Boolean =
        withTimeoutOrNull(RECORDING_STOP_WAIT_MS) {
            recordings.recordings.first { rows ->
                rows.firstOrNull { it.recordingId == recordingId }?.status !in setOf(
                    LiveRecordingStatus.STARTING,
                    LiveRecordingStatus.RECORDING,
                    LiveRecordingStatus.FINALIZING,
                )
            }
            true
        } ?: false

    private suspend fun restoreScheduled(captured: List<LiveRecording>) {
        captured.filter { it.status == LiveRecordingStatus.SCHEDULED }.forEach { original ->
            val current = recordings.get(original.recordingId)
            if (current?.status == LiveRecordingStatus.CANCELLED) {
                recordings.put(original)
            }
        }
        scheduler.restoreScheduledAlarms()
    }

    private suspend fun sourceStillExists(sourceId: SourceId): Boolean =
        try {
            sourceDao.get(sourceId.value) != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            true
        }

    private companion object {
        const val RECORDING_STOP_WAIT_MS = 8_000L
    }
}

class LiveRecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val recordingId = intent?.getStringExtra(AndroidLiveRecordingScheduler.EXTRA_RECORDING_ID)
            ?.takeIf(String::isNotBlank) ?: return
        val application = context.applicationContext as? OwnPlayApplication ?: return
        application.services.liveRecordingScheduler.startScheduled(recordingId)
    }
}

class LiveRecordingRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (
            intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent?.action != ACTION_EXACT_ALARM_PERMISSION_CHANGED
        ) return
        val application = context.applicationContext as? OwnPlayApplication ?: return
        application.services.liveRecordingScheduler.restoreScheduledAlarms()
    }
}

class LiveRecordingService : Service() {
    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
    )
    private val active = AtomicBoolean(false)
    private var captureJob: Job? = null
    private var statusJob: Job? = null
    private var activeRecordingId: String? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recordingId = intent?.getStringExtra(AndroidLiveRecordingScheduler.EXTRA_RECORDING_ID)
        if (recordingId.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_SAVE) {
            val application = application as OwnPlayApplication
            val recording = application.services.liveRecordingRepository.get(recordingId)
            if (recording?.status in setOf(LiveRecordingStatus.STARTING, LiveRecordingStatus.RECORDING)) {
                application.services.liveRecordingRepository.update(recordingId, LiveRecordingTransitionPolicy::stopAndSave)
                captureJob?.cancel(CancellationException("Stop and save requested"))
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PREEMPT_FOR_PLAYBACK) {
            val ownPlay = application as OwnPlayApplication
            if (recordingId == activeRecordingId) {
                var shouldCancelCapture = false
                ownPlay.services.liveRecordingRepository.update(recordingId) { current ->
                    if (current.status !in setOf(LiveRecordingStatus.STARTING, LiveRecordingStatus.RECORDING)) {
                        current
                    } else {
                        shouldCancelCapture = true
                        current.copy(
                            failureReason = LiveRecordingFailureCodes.PLAYBACK_PREEMPTED_RECORDING,
                            failureReasonCode = LiveRecordingFailureCodes.PLAYBACK_PREEMPTED_RECORDING,
                            failureStage = LiveRecordingFailureStages.PLAYBACK_PREEMPTION,
                            failureAtEpochMs = System.currentTimeMillis(),
                            safeError = "Live playback took priority; saving a verified partial recording if possible.",
                        )
                    }
                }
                if (shouldCancelCapture) {
                    captureJob?.cancel(CancellationException("Live playback preempted recording"))
                }
            }
            return START_NOT_STICKY
        }
        if (!active.compareAndSet(false, true)) {
            val ownPlay = application as OwnPlayApplication
            ownPlay.services.liveRecordingRepository.get(recordingId)
                ?.takeIf {
                    it.status == LiveRecordingStatus.SCHEDULED && recordingId != activeRecordingId
                }
                ?.let { recording ->
                    ownPlay.services.liveCapacityCoordinator.releaseRecording(recordingId)
                    ownPlay.services.liveRecordingRepository.put(
                        recording.copy(
                            status = LiveRecordingStatus.FAILED,
                            safeError = "OwnPlay's single-recording service is already handling another recording.",
                            failureReason = LiveRecordingFailureCodes.APP_RECORDING_SERVICE_BUSY,
                            failureReasonCode = LiveRecordingFailureCodes.APP_RECORDING_SERVICE_BUSY,
                            failureStage = LiveRecordingFailureStages.FOREGROUND_SERVICE,
                            failureAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
            return START_NOT_STICKY
        }
        activeRecordingId = recordingId
        val appServices = (application as OwnPlayApplication).services
        val recording = appServices.liveRecordingRepository.get(recordingId)
        if (recording?.status != LiveRecordingStatus.SCHEDULED) {
            appServices.liveCapacityCoordinator.releaseRecording(recordingId)
            active.set(false)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            appServices.liveCapacityCoordinator.releaseRecording(recordingId)
            appServices.liveRecordingRepository.put(
                recording.copy(
                    status = LiveRecordingStatus.FAILED,
                    safeError = "Allow notifications in Android settings before starting a recording.",
                    failureReason = LiveRecordingFailureCodes.NOTIFICATION_PERMISSION_MISSING,
                    failureReasonCode = LiveRecordingFailureCodes.NOTIFICATION_PERMISSION_MISSING,
                    failureStage = LiveRecordingFailureStages.FOREGROUND_SERVICE,
                    failureAtEpochMs = System.currentTimeMillis(),
                ),
            )
            active.set(false)
            activeRecordingId = null
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(recording.title)
            .setContentText(recordingStatusText(recording))
            .setOngoing(true)
            .addAction(
                R.drawable.ic_download_notification,
                "Stop & Save",
                stopSavePendingIntent(recordingId),
            )
            .setContentIntent(managePendingIntent(recordingId))
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Exception) {
            appServices.liveCapacityCoordinator.releaseRecording(recordingId)
            appServices.liveRecordingRepository.put(
                recording.copy(
                    status = LiveRecordingStatus.FAILED,
                    safeError = "Android did not allow OwnPlay to start the recording service.",
                    failureReason = LiveRecordingFailureCodes.ANDROID_BACKGROUND_START_DENIED,
                    failureReasonCode = LiveRecordingFailureCodes.ANDROID_BACKGROUND_START_DENIED,
                    failureStage = LiveRecordingFailureStages.FOREGROUND_SERVICE,
                    failureAtEpochMs = System.currentTimeMillis(),
                ),
            )
            active.set(false)
            activeRecordingId = null
            stopSelf(startId)
            return START_NOT_STICKY
        }
        statusJob = serviceScope.launch {
            while (true) {
                delay(30_000L)
                val current = appServices.liveRecordingRepository.get(recordingId) ?: break
                runCatching {
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        Notification.Builder(this@LiveRecordingService, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_download_notification)
                            .setContentTitle(current.title)
                            .setContentText(recordingStatusText(current))
                            .setOngoing(true)
                            .addAction(
                                R.drawable.ic_download_notification,
                                "Stop & Save",
                                stopSavePendingIntent(recordingId),
                            )
                            .setContentIntent(managePendingIntent(recordingId))
                            .build(),
                    )
                }
            }
        }
        captureJob = serviceScope.launch {
            try {
                appServices.liveRecordingExecutor.execute(recordingId)
            } finally {
                statusJob?.cancel()
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                stopForeground(STOP_FOREGROUND_REMOVE)
                active.set(false)
                activeRecordingId = null
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val appServices = (application as? OwnPlayApplication)?.services
        val recordingId = activeRecordingId
        recordingId?.let { id ->
            appServices?.liveRecordingRepository?.get(id)?.let { current ->
                appServices.liveRecordingRepository.put(
                    current.copy(
                        status = LiveRecordingStatus.FAILED,
                        safeError = "Android ended the recording service time allowance.",
                        failureReason = LiveRecordingFailureCodes.ANDROID_SERVICE_TIME_LIMIT,
                        failureReasonCode = LiveRecordingFailureCodes.ANDROID_SERVICE_TIME_LIMIT,
                        failureStage = LiveRecordingFailureStages.FOREGROUND_SERVICE,
                        failureAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
        captureJob?.cancel(CancellationException("Android foreground-service limit reached"))
        statusJob?.cancel()
        val finalizationJob = captureJob
        serviceScope.launch {
            val finalized = withTimeoutOrNull(TIMEOUT_FINALIZATION_WINDOW_MS) {
                finalizationJob?.join()
                true
            } ?: false
            if (!finalized) {
                recordingId?.let { id ->
                    appServices?.liveRecordingRepository?.get(id)?.let { current ->
                        if (
                            current.status == LiveRecordingStatus.STARTING || current.status == LiveRecordingStatus.RECORDING ||
                            current.status == LiveRecordingStatus.FINALIZING
                        ) {
                            appServices.liveRecordingRepository.put(
                                current.copy(
                                    status = LiveRecordingStatus.FAILED,
                                    safeError = "Android ended the recording service time allowance.",
                                    failureReason = LiveRecordingFailureCodes.ANDROID_SERVICE_TIME_LIMIT,
                                    failureReasonCode = LiveRecordingFailureCodes.ANDROID_SERVICE_TIME_LIMIT,
                                    failureStage = LiveRecordingFailureStages.FOREGROUND_SERVICE,
                                    failureAtEpochMs = current.failureAtEpochMs ?: System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                active.set(false)
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopSavePendingIntent(recordingId: String): PendingIntent {
        val intent = Intent(this, LiveRecordingService::class.java)
            .setAction(ACTION_STOP_SAVE)
            .setData(Uri.parse("ownplay://recording/stop/" + Uri.encode(recordingId)))
            .putExtra(AndroidLiveRecordingScheduler.EXTRA_RECORDING_ID, recordingId)
        return PendingIntent.getService(
            this,
            recordingId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun managePendingIntent(recordingId: String): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(DownloadNotificationNavigationContract.ACTION_OPEN_RECORDINGS)
            .setData(Uri.parse("ownplay://recording/manage/" + Uri.encode(recordingId)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            recordingId.hashCode() xor 0x4C52,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun recordingStatusText(recording: LiveRecording): String {
        if (recording.status == LiveRecordingStatus.FINALIZING) return "Saving recording…"
        val startedAt = recording.actualStartEpochSeconds ?: return "Preparing / connecting · waiting for media"
        val elapsed = ((System.currentTimeMillis() / 1_000L) - startedAt).coerceAtLeast(0L)
        val remaining = (recording.deadlineEpochSeconds - System.currentTimeMillis() / 1_000L)
            .coerceAtLeast(0L)
        return "Recording · ${formatDuration(elapsed)} elapsed · ${formatDuration(remaining)} left"
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60L
        return if (minutes >= 60L) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Live recordings",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP_SAVE = "app.ownplay.mobile.action.STOP_AND_SAVE_RECORDING"
        const val ACTION_PREEMPT_FOR_PLAYBACK = "app.ownplay.mobile.action.PREEMPT_RECORDING_FOR_PLAYBACK"
        private const val CHANNEL_ID = "live_recordings"
        private const val NOTIFICATION_ID = 0x4C52
        private const val TIMEOUT_FINALIZATION_WINDOW_MS = 2_500L
    }
}
