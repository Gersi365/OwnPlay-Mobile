package app.ownplay.player.source.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

object SourceHttpClient {
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followSslRedirects(false)
            .build()
    }
}
