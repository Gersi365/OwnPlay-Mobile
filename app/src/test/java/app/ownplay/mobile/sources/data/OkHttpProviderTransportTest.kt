package app.ownplay.mobile.sources.data

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpProviderTransportTest {

    @Test
    fun directHttpProviderAndSameSchemeHttpsRedirectRemainSupported() = runBlocking {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val httpServer = httpServer()
        val tlsServer = httpsServer(certificate)
        try {
            tlsServer.createContext("/") { exchange ->
                if (exchange.requestURI.path == "/") {
                    exchange.responseHeaders.add("Location", "/final")
                    exchange.sendResponseHeaders(302, -1)
                } else {
                    val body = "secure".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                exchange.close()
            }
            tlsServer.start()

            val transport = OkHttpProviderTransport(trustingClient(certificate))
            val directHttp = transport.get("http://127.0.0.1:${httpServer.address.port}/provider")
            val httpsRedirect = transport.get("https://127.0.0.1:${tlsServer.address.port}/")

            assertEquals(200, directHttp.statusCode)
            assertEquals("text/plain", directHttp.contentType?.substringBefore(';'))
            assertEquals(200, httpsRedirect.statusCode)
            assertEquals("secure", httpsRedirect.body)
        } finally {
            httpServer.stop(0)
            tlsServer.stop(0)
        }
    }

    @Test
    fun httpsRedirectToHttpIsNotFollowed() = runBlocking {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        var cleartextRequests = 0
        val httpServer = httpServer { cleartextRequests += 1 }
        val tlsServer = httpsServer(certificate) { exchange ->
            exchange.responseHeaders.add(
                "Location",
                "http://127.0.0.1:${httpServer.address.port}/credential-bearing-target",
            )
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        try {
            tlsServer.start()
            val transport = OkHttpProviderTransport(trustingClient(certificate))

            val response = transport.get("https://127.0.0.1:${tlsServer.address.port}/provider")

            assertEquals(302, response.statusCode)
            assertEquals(0, cleartextRequests)
        } finally {
            httpServer.stop(0)
            tlsServer.stop(0)
        }
    }

    @Test
    fun coroutineCancellationClosesTheUnderlyingHttpCall() = runBlocking {
        val server = ServerSocket(0, 1, loopbackAddress())
        val accepted = CompletableDeferred<Unit>()
        val serverJob = async(Dispatchers.IO) {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                var headerEnd = 0
                while (headerEnd < 4) {
                    val byte = input.read()
                    if (byte < 0) break
                    headerEnd = when {
                        headerEnd == 0 && byte == '\r'.code -> 1
                        headerEnd == 1 && byte == '\n'.code -> 2
                        headerEnd == 2 && byte == '\r'.code -> 3
                        headerEnd == 3 && byte == '\n'.code -> 4
                        byte == '\r'.code -> 1
                        else -> 0
                    }
                }
                accepted.complete(Unit)
                input.read()
            }
        }

        try {
            val request = async {
                OkHttpProviderTransport().get("http://127.0.0.1:${server.localPort}/slow")
            }
            withTimeout(2_000) { accepted.await() }
            request.cancelAndJoin()

            assertTrue(request.isCancelled)
            assertEquals(-1, withTimeout(2_000) { serverJob.await() })
        } finally {
            server.close()
        }
    }

    private fun httpServer(onRequest: () -> Unit = {}): HttpServer =
        HttpServer.create(InetSocketAddress(loopbackAddress(), 0), 0).apply {
            executor = daemonExecutor()
            createContext("/") { exchange ->
                onRequest()
                val body = "http".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    private fun httpsServer(
        certificate: HeldCertificate,
        onRequest: ((com.sun.net.httpserver.HttpExchange) -> Unit)? = null,
    ): HttpsServer = HttpsServer.create(
        InetSocketAddress(loopbackAddress(), 0),
        0,
    ).apply {
        httpsConfigurator = HttpsConfigurator(serverSslContext(certificate))
        executor = daemonExecutor()
        if (onRequest != null) {
            createContext("/", HttpHandler { exchange -> onRequest(exchange) })
        }
    }

    private fun trustingClient(certificate: HeldCertificate): OkHttpClient {
        val trust = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        return OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .build()
    }

    private fun serverSslContext(certificate: HeldCertificate): SSLContext {
        val password = "test-only-fixture".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("fixture", certificate.keyPair.private, password, arrayOf(certificate.certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
        return SSLContext.getInstance("TLS").apply {
            init(keyManagers.keyManagers, null, SecureRandom())
        }
    }

    private fun daemonExecutor() = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ownplay-provider-test-server").apply { isDaemon = true }
    }

    private fun loopbackAddress() = InetAddress.getByName("127.0.0.1")
}
