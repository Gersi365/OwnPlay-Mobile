package app.ownplay.player.source.m3u

import android.content.ContentResolver
import android.net.Uri
import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceResult
import app.ownplay.player.source.SourceValidator
import app.ownplay.player.source.UrlValidationResult
import app.ownplay.player.source.network.SourceHttpClient
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class RemoteM3uLoader(
    private val httpClient: OkHttpClient = SourceHttpClient.shared,
    private val allowCleartext: Boolean = false,
) {
    suspend fun load(
        playlistUrl: String,
        allowCleartext: Boolean = this.allowCleartext,
    ): SourceResult<M3uPlaylist> {
        val validation = SourceValidator.validateRemotePlaylistUrl(playlistUrl)
        if (validation is UrlValidationResult.Invalid) {
            return SourceResult.Failure(validation.error)
        }
        val valid = validation as UrlValidationResult.Valid
        if (valid.usesCleartext && !allowCleartext) {
            return SourceResult.Failure(SourceError.CleartextTransportRequiresOptIn)
        }

        val url = valid.normalizedUrl.toHttpUrlOrNull()
            ?: return SourceResult.Failure(SourceError.InvalidUrl)
        val request = Request.Builder()
            .url(url)
            .header(
                "Accept",
                "audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*",
            )
            .get()
            .build()

        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 || response.code == 403 -> {
                            SourceResult.Failure(SourceError.AuthenticationFailed)
                        }

                        response.code == 408 || response.code == 504 -> {
                            SourceResult.Failure(SourceError.Timeout)
                        }

                        !response.isSuccessful -> {
                            SourceResult.Failure(SourceError.HttpFailure(response.code))
                        }

                        else -> response.body
                            .charStream()
                            .buffered()
                            .useLines { lines -> M3uParser.parse(lines) }
                            .toSourceResult()
                    }
                }
            } catch (_: SocketTimeoutException) {
                SourceResult.Failure(SourceError.Timeout)
            } catch (_: SSLException) {
                SourceResult.Failure(SourceError.SecureConnectionFailed)
            } catch (_: UnknownHostException) {
                SourceResult.Failure(SourceError.NetworkUnavailable)
            } catch (_: ConnectException) {
                SourceResult.Failure(SourceError.NetworkUnavailable)
            } catch (_: NoRouteToHostException) {
                SourceResult.Failure(SourceError.NetworkUnavailable)
            } catch (_: IOException) {
                SourceResult.Failure(SourceError.NetworkUnavailable)
            } catch (_: IllegalArgumentException) {
                SourceResult.Failure(SourceError.InvalidUrl)
            }
        }
    }
}

class AndroidLocalM3uLoader(
    private val contentResolver: ContentResolver,
) {
    suspend fun load(documentUri: String): SourceResult<M3uPlaylist> {
        SourceValidator.validateLocalDocumentUri(documentUri)?.let { error ->
            return SourceResult.Failure(error)
        }
        val uri = runCatching { Uri.parse(documentUri.trim()) }
            .getOrElse { return SourceResult.Failure(SourceError.UnsupportedLocalUri) }

        return withContext(Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: return@withContext SourceResult.Failure(SourceError.SourceReadFailed)
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    M3uParser.parse(lines).toSourceResult()
                }
            } catch (_: SecurityException) {
                SourceResult.Failure(SourceError.SourceReadFailed)
            } catch (_: IOException) {
                SourceResult.Failure(SourceError.SourceReadFailed)
            } catch (_: IllegalArgumentException) {
                SourceResult.Failure(SourceError.SourceReadFailed)
            }
        }
    }
}

private fun M3uPlaylist.toSourceResult(): SourceResult<M3uPlaylist> =
    if (entries.isEmpty()) {
        SourceResult.Failure(SourceError.MalformedPlaylist)
    } else {
        SourceResult.Success(this)
    }
