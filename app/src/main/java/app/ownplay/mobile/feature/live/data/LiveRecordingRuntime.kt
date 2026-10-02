package app.ownplay.mobile.feature.live.data

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.R
import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingRepository
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    )

    companion object {
        private const val PREFERENCES_NAME = "ownplay_live_recordings"
        private const val RECORDINGS_KEY = "items"
    }
}

enum class LiveRecordingScheduleResult {
    SCHEDULED,
    EXACT_ALARM_ACCESS_REQUIRED,
    OVERLAPPING_RECORDING,
    FAILED,
}

class AndroidLiveRecordingScheduler(
    context: Context,
    private val repository: LiveRecordingRepository,
) {
    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun schedule(recording: LiveRecording): LiveRecordingScheduleResult {
        if (!canScheduleExactAlarms()) {
            return LiveRecordingScheduleResult.EXACT_ALARM_ACCESS_REQUIRED
        }
        if (hasOverlap(recording)) return LiveRecordingScheduleResult.OVERLAPPING_RECORDING
        if (!repository.put(recording.copy(status = LiveRecordingStatus.SCHEDULED))) {
            return LiveRecordingScheduleResult.FAILED
        }
        return runCatching {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                recording.startEpochSeconds * 1_000L,
                alarmIntent(recording.recordingId),
            )
            LiveRecordingScheduleResult.SCHEDULED
        }.getOrElse {
            repository.put(
                recording.copy(
                    status = LiveRecordingStatus.FAILED,
                    safeError = SAFE_SCHEDULE_ERROR,
                ),
            )
            LiveRecordingScheduleResult.FAILED
        }
    }

    fun startNow(recording: LiveRecording): Boolean {
        if (hasOverlap(recording)) return false
        if (!repository.put(recording.copy(status = LiveRecordingStatus.SCHEDULED))) return false
        return runCatching {
            applicationContext.startForegroundService(serviceIntent(recording.recordingId))
            true
        }.getOrElse {
            repository.put(
                recording.copy(
                    status = LiveRecordingStatus.FAILED,
                    safeError = SAFE_START_ERROR,
                ),
            )
            false
        }
    }

    private fun hasOverlap(recording: LiveRecording): Boolean =
        repository.recordings.value.any { existing ->
            existing.recordingId != recording.recordingId &&
                existing.status in setOf(LiveRecordingStatus.SCHEDULED, LiveRecordingStatus.RECORDING) &&
                existing.startEpochSeconds < recording.endEpochSeconds &&
                recording.startEpochSeconds < existing.endEpochSeconds
        }

    fun cancel(recordingId: String): Boolean {
        alarmManager.cancel(alarmIntent(recordingId))
        val recording = repository.get(recordingId) ?: return true
        return repository.put(recording.copy(status = LiveRecordingStatus.CANCELLED))
    }

    fun restoreScheduledAlarms() {
        val now = System.currentTimeMillis() / 1_000L
        repository.recordings.value
            .filter { it.status == LiveRecordingStatus.RECORDING }
            .forEach { recording ->
                repository.put(
                    recording.copy(
                        status = LiveRecordingStatus.FAILED,
                        safeError = SAFE_INTERRUPTED_ERROR,
                    ),
                )
            }
        if (!canScheduleExactAlarms()) {
            repository.recordings.value
                .filter { it.status == LiveRecordingStatus.SCHEDULED }
                .forEach { recording ->
                    repository.put(
                        recording.copy(
                            status = LiveRecordingStatus.FAILED,
                            safeError = SAFE_EXACT_ALARM_ACCESS_ERROR,
                        ),
                    )
                }
            return
        }
        repository.recordings.value
            .filter { it.status == LiveRecordingStatus.SCHEDULED }
            .forEach { recording ->
                if (recording.endEpochSeconds <= now) {
                    repository.put(
                        recording.copy(
                            status = LiveRecordingStatus.FAILED,
                            safeError = SAFE_MISSED_ERROR,
                        ),
                    )
                } else {
                    val trigger = recording.startEpochSeconds.coerceAtLeast(now)
                    runCatching {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            trigger * 1_000L,
                            alarmIntent(recording.recordingId),
                        )
                    }.onFailure {
                        repository.put(
                            recording.copy(
                                status = LiveRecordingStatus.FAILED,
                                safeError = SAFE_SCHEDULE_ERROR,
                            ),
                        )
                    }
                }
            }
    }

    internal fun startScheduled(recordingId: String): Boolean =
        runCatching {
            applicationContext.startForegroundService(serviceIntent(recordingId))
            true
        }.getOrElse {
            repository.get(recordingId)?.let { current ->
                repository.put(
                    current.copy(
                        status = LiveRecordingStatus.FAILED,
                        safeError = SAFE_START_ERROR,
                    ),
                )
            }
            false
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
        private const val SAFE_SCHEDULE_ERROR = "Android could not schedule this recording."
        private const val SAFE_START_ERROR = "Android could not start this recording."
        private const val SAFE_MISSED_ERROR = "The scheduled recording window has passed."
        private const val SAFE_INTERRUPTED_ERROR = "Recording stopped when OwnPlay was interrupted."
        private const val SAFE_EXACT_ALARM_ACCESS_ERROR = "Grant exact-alarm access to restore this recording."
    }
}

class LiveRecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val recordingId = intent?.getStringExtra(AndroidLiveRecordingScheduler.EXTRA_RECORDING_ID)
            ?.takeIf(String::isNotBlank) ?: return
        val repository = SharedPreferencesLiveRecordingRepository(context)
        AndroidLiveRecordingScheduler(context, repository).startScheduled(recordingId)
    }
}

class LiveRecordingRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (
            intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent?.action != ACTION_EXACT_ALARM_PERMISSION_CHANGED
        ) return
        val repository = SharedPreferencesLiveRecordingRepository(context)
        AndroidLiveRecordingScheduler(context, repository).restoreScheduledAlarms()
    }
}

class LiveRecordingService : Service() {
    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
    )
    private val active = AtomicBoolean(false)

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
        if (!active.compareAndSet(false, true)) {
            val ownPlay = application as OwnPlayApplication
            ownPlay.services.liveRecordingRepository.get(recordingId)
                ?.takeIf { it.status == LiveRecordingStatus.SCHEDULED }
                ?.let { recording ->
                    ownPlay.services.liveRecordingRepository.put(
                        recording.copy(
                            status = LiveRecordingStatus.FAILED,
                            safeError = "Another Live recording is already active.",
                        ),
                    )
                }
            return START_NOT_STICKY
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle("OwnPlay recording")
            .setContentText("Recording a Live program")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        serviceScope.launch {
            val application = application as OwnPlayApplication
            application.services.liveRecordingExecutor.execute(recordingId)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
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
        private const val CHANNEL_ID = "live_recordings"
        private const val NOTIFICATION_ID = 0x4C52
    }
}
