package app.ownplay.mobile.downloads.data

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpRedirectSecurityTest {
    @Test
    fun keepsSameSchemeRedirectsButDisablesCrossSchemeRedirects() {
        val guarded = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
            .withCrossSchemeRedirectsDisabled()

        assertTrue(guarded.followRedirects)
        assertFalse(guarded.followSslRedirects)
    }
}
