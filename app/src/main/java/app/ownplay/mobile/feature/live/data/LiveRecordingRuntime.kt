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
import app.ownplay.mobile.MainActivity
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.R
import app.ownplay.mobile.downloads.data.DownloadNotificationNavigationContract
import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingRepository
import app.ownplay.mobile.feature.live.domain.LiveRecordingFinalizationState
import app.ownplay.mobile.feature.live.domain.LiveRecordingStopPolicy
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import app.ownplay.mobile.feature.live.domain.LiveRecordingTransitionPolicy
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
        if (!canScheduleExactAlarms()) {
            return LiveRecordingScheduleResult.EXACT_ALARM_ACCESS_REQUIRED
        }
        val scheduled = when {
            existing != null && existing.status == LiveRecordingStatus.SCHEDULED -> existing
            existing != null -> {
                if (hasOverlap(recording)) return LiveRecordingScheduleResult.OVERLAPPING_RECORDING
                recording.copy(status = LiveRecordingStatus.SCHEDULED).also { retry ->
                    if (!repository.put(retry)) return LiveRecordingScheduleResult.FAILED
                }
            }
            else -> {
                if (hasOverlap(recording)) return LiveRecordingScheduleResult.OVERLAPPING_RECORDING
                recording.copy(status = LiveRecordingStatus.SCHEDULED).also { fresh ->
                    if (!repository.put(fresh)) return LiveRecordingScheduleResult.FAILED
                }
            }
        }
        return runCatching {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                scheduled.startEpochSeconds * 1_000L,
                alarmIntent(scheduled.recordingId),
            )
            LiveRecordingScheduleResult.SCHEDULED
        }.getOrElse {
            repository.put(
                scheduled.copy(
                    status = LiveRecordingStatus.FAILED,
                    safeError = SAFE_SCHEDULE_ERROR,
                ),
            )
            LiveRecordingScheduleResult.FAILED
        }
    }

    fun startNow(recording: LiveRecording): Boolean {
        val existing = repository.get(recording.recordingId)
        if (existing?.status in setOf(LiveRecordingStatus.STARTING, LiveRecordingStatus.RECORDING, LiveRecordingStatus.FINALIZING)) return true
        if (
            existing != null && existing.status !in setOf(
                LiveRecordingStatus.SCHEDULED,
                LiveRecordingStatus.FAILED,
                LiveRecordingStatus.CANCELLED,
            )
        ) return false
        if (hasOverlap(recording)) return false
        if (
            (existing == null || existing.status != LiveRecordingStatus.SCHEDULED) &&
            !repository.put(recording.copy(status = LiveRecordingStatus.SCHEDULED))
        ) {
            return false
        }
        return runCatching {
            applicationContext.startForegroundService(serviceIntent(recording.recordingId))
            true
        }.getOrElse {
            repository.put(
                (repository.get(recording.recordingId) ?: recording).copy(
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
                existing.status in setOf(LiveRecordingStatus.SCHEDULED, LiveRecordingStatus.STARTING, LiveRecordingStatus.RECORDING, LiveRecordingStatus.FINALIZING) &&
                existing.startEpochSeconds < recording.endEpochSeconds &&
                recording.startEpochSeconds < existing.endEpochSeconds
        }

    fun cancel(recordingId: String): Boolean {
        alarmManager.cancel(alarmIntent(recordingId))
        val recording = repository.get(recordingId) ?: return true
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
        val now = System.currentTimeMillis() / 1_000L
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
        private const val SAFE_EXACT_ALARM_ACCESS_ERROR = "Grant exact-alarm access to restore this recording."
    }
}

class LiveRecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val recordingId = intent?.getStringExtra(AndroidLiveRecordingScheduler.EXTRA_RECORDING_ID)
            ?.takeIf(String::isNotBlank) ?: return
        val application = context.applicationContext as? OwnPlayApplication ?: return
        AndroidLiveRecordingScheduler(context, application.services.liveRecordingRepository)
            .startScheduled(recordingId)
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
        AndroidLiveRecordingScheduler(context, application.services.liveRecordingRepository)
            .restoreScheduledAlarms()
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
        if (!active.compareAndSet(false, true)) {
            val ownPlay = application as OwnPlayApplication
            ownPlay.services.liveRecordingRepository.get(recordingId)
                ?.takeIf {
                    it.status == LiveRecordingStatus.SCHEDULED && recordingId != activeRecordingId
                }
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
        activeRecordingId = recordingId
        val appServices = (application as OwnPlayApplication).services
        val recording = appServices.liveRecordingRepository.get(recordingId)
        if (recording?.status != LiveRecordingStatus.SCHEDULED) {
            active.set(false)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
                        failureReason = "ANDROID_FGS_TIME_LIMIT",
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
                                    failureReason = "ANDROID_FGS_TIME_LIMIT",
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
        private const val CHANNEL_ID = "live_recordings"
        private const val NOTIFICATION_ID = 0x4C52
        private const val TIMEOUT_FINALIZATION_WINDOW_MS = 2_500L
    }
}
