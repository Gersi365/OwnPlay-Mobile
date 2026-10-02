package app.ownplay.mobile

import android.app.Application
import app.ownplay.mobile.core.OwnPlayServices

class OwnPlayApplication : Application() {
    private val servicesDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OwnPlayServices.create(this)
    }

    val services: OwnPlayServices
        get() = servicesDelegate.value

    override fun onTerminate() {
        if (servicesDelegate.isInitialized()) {
            services.releasePlayback()
        }
        super.onTerminate()
    }
}
