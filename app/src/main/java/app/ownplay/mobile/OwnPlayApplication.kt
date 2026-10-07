package app.ownplay.mobile

import android.app.Application
import app.ownplay.mobile.core.OwnPlayServices
import app.ownplay.mobile.feature.settings.data.SourceRefreshSchedulePreferences
import app.ownplay.mobile.feature.settings.data.SourceRefreshStartupCatchUpCoordinator
import app.ownplay.mobile.feature.settings.data.WorkManagerSourceRefreshScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OwnPlayApplication : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servicesDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OwnPlayServices.create(this)
    }

    val services: OwnPlayServices
        get() = servicesDelegate.value

    override fun onCreate() {
        super.onCreate()
        startupScope.launch {
            try {
                val current = services
                SourceRefreshStartupCatchUpCoordinator(
                    sourceDao = current.database.sourceDao(),
                    refreshStateDao = current.database.refreshStateDao(),
                    preferences = SourceRefreshSchedulePreferences(applicationContext),
                    scheduler = WorkManagerSourceRefreshScheduler(applicationContext),
                ).enqueueDueSources()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed catch-up check must not prevent cached local screens from opening.
            }
        }
    }

    override fun onTerminate() {
        if (servicesDelegate.isInitialized()) {
            services.releasePlayback()
        }
        super.onTerminate()
    }
}
