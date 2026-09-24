package app.ownplay.mobile.sources.data

import app.ownplay.mobile.sources.data.m3u.M3uClientException
import app.ownplay.mobile.sources.data.m3u.M3uClientFailureCategory
import app.ownplay.mobile.sources.data.m3u.OkHttpM3uClient
import app.ownplay.mobile.sources.data.xtream.OkHttpXtreamClient
import app.ownplay.mobile.sources.data.xtream.XtreamClientException
import app.ownplay.mobile.sources.data.xtream.XtreamClientFailureCategory
import app.ownplay.mobile.sources.data.xtream.XtreamConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderClientFailureClassificationTest {
    @Test
    fun xtreamClassifies429And5xxAsTransientButKeepsOther4xxProviderErrorsTerminal() = runBlocking {
        assertEquals(
            XtreamClientFailureCategory.TRANSIENT_PROVIDER,
            xtreamFailure(429),
        )
        assertEquals(
            XtreamClientFailureCategory.TRANSIENT_PROVIDER,
            xtreamFailure(503),
        )
        assertEquals(
            XtreamClientFailureCategory.PROVIDER,
            xtreamFailure(400),
        )
        assertEquals(
            XtreamClientFailureCategory.AUTHENTICATION,
            xtreamFailure(401),
        )
    }

    @Test
    fun xtreamTreatsHttp200AuthZeroAsAuthenticationFailure() = runBlocking {
        val client = OkHttpXtreamClient(
            object : ProviderTransport {
                override suspend fun get(url: String): ProviderResponse =
                    ProviderResponse(
                        statusCode = 200,
                        contentType = "application/json",
                        body = """{"user_info":{"auth":0,"status":"Disabled"}}""",
                    )
            },
        )

        val category = try {
            client.accountInfo(XtreamConnection("https://example.invalid", "user", "pass"))
            error("Expected authentication failure")
        } catch (error: XtreamClientException) {
            error.category
        }

        assertEquals(XtreamClientFailureCategory.AUTHENTICATION, category)
    }

    @Test
    fun m3uClassifies429And5xxAsTransientButKeepsOther4xxProviderErrorsTerminal() = runBlocking {
        assertEquals(M3uClientFailureCategory.TRANSIENT_PROVIDER, m3uFailure(429))
        assertEquals(M3uClientFailureCategory.TRANSIENT_PROVIDER, m3uFailure(502))
        assertEquals(M3uClientFailureCategory.PROVIDER, m3uFailure(404))
        assertEquals(M3uClientFailureCategory.AUTHENTICATION, m3uFailure(403))
    }

    private suspend fun xtreamFailure(status: Int): XtreamClientFailureCategory {
        val client = OkHttpXtreamClient(FixedStatusTransport(status))
        return try {
            client.liveCategories(XtreamConnection("https://example.invalid", "user", "pass"))
            error("Expected Xtream failure for HTTP $status")
        } catch (error: XtreamClientException) {
            error.category
        }
    }

    private suspend fun m3uFailure(status: Int): M3uClientFailureCategory {
        val client = OkHttpM3uClient(FixedStatusTransport(status))
        return try {
            client.fetch("https://example.invalid/list.m3u")
            error("Expected M3U failure for HTTP $status")
        } catch (error: M3uClientException) {
            error.category
        }
    }

    private class FixedStatusTransport(private val status: Int) : ProviderTransport {
        override suspend fun get(url: String): ProviderResponse =
            ProviderResponse(statusCode = status, contentType = "text/plain", body = "error")
    }
}
