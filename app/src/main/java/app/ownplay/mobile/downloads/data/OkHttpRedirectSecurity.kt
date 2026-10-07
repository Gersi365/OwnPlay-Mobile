package app.ownplay.mobile.downloads.data

import okhttp3.OkHttpClient

/**
 * Keeps ordinary same-scheme redirects and direct HTTP sources working, but prevents OkHttp
 * from following HTTP <-> HTTPS redirects. This protects credential-bearing HTTPS requests
 * from being silently downgraded to cleartext.
 */
internal fun OkHttpClient.withCrossSchemeRedirectsDisabled(): OkHttpClient =
    newBuilder()
        .followRedirects(true)
        .followSslRedirects(false)
        .build()
