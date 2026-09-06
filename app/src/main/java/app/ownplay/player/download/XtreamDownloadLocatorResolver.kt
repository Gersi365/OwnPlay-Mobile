package app.ownplay.player.download

import app.ownplay.player.persistence.OwnPlayDatabase
import app.ownplay.player.persistence.SourceKinds
import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.persistence.download.MediaDownloadEntity
import app.ownplay.player.persistence.secure.SensitiveValueRef
import app.ownplay.player.persistence.secure.SensitiveValueStore
import app.ownplay.player.source.CredentialRef
import app.ownplay.player.source.SourceValidator
import app.ownplay.player.source.UrlValidationResult
import app.ownplay.player.source.credential.CredentialStore
import app.ownplay.player.source.xtream.XtreamSourceLocatorCodec
import java.util.concurrent.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class ResolvedDownloadLocator(
    val value: String,
) {
    override fun toString(): String = "ResolvedDownloadLocator(value=<redacted>)"
}

internal sealed interface DownloadLocatorResult {
    data class Success(val locator: ResolvedDownloadLocator) : DownloadLocatorResult
    data class Failure(val reason: String) : DownloadLocatorResult
}

internal class XtreamDownloadLocatorResolver(
    private val database: OwnPlayDatabase,
    private val sensitiveValueStore: SensitiveValueStore,
    private val credentialStore: CredentialStore,
) {
    suspend fun resolve(download: MediaDownloadEntity): DownloadLocatorResult {
        if (download.providerStreamId <= 0) {
            return DownloadLocatorResult.Failure("Invalid provider stream id")
        }
        val source = try {
            database.playlistSourceDao().getById(download.sourceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DownloadLocatorResult.Failure("Source lookup failed")
        } ?: return DownloadLocatorResult.Failure("Source no longer exists")

        if (!source.enabled) {
            return DownloadLocatorResult.Failure("Source is disabled")
        }
        if (source.sourceKind != SourceKinds.XTREAM) {
            return DownloadLocatorResult.Failure("Downloads currently require an Xtream source")
        }

        val locatorRef = try {
            SensitiveValueRef(source.locatorRef)
        } catch (_: IllegalArgumentException) {
            return DownloadLocatorResult.Failure("Source locator is invalid")
        }
        val storedLocator = try {
            sensitiveValueStore.get(locatorRef)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DownloadLocatorResult.Failure("Secure source lookup failed")
        } ?: return DownloadLocatorResult.Failure("Source locator is unavailable")
        val sourceLocator = XtreamSourceLocatorCodec.parse(storedLocator)
            ?: return DownloadLocatorResult.Failure("Source locator is invalid")
        val server = when (val validation = SourceValidator.validateXtreamServer(sourceLocator.serverUrl)) {
            is UrlValidationResult.Invalid -> {
                return DownloadLocatorResult.Failure("Source server is invalid")
            }
            is UrlValidationResult.Valid -> validation
        }
        if (server.usesCleartext && !sourceLocator.allowCleartext) {
            return DownloadLocatorResult.Failure("Cleartext source is not enabled")
        }

        val credentialValue = source.credentialRef
            ?: return DownloadLocatorResult.Failure("Source credentials are unavailable")
        val credentialRef = try {
            CredentialRef(credentialValue)
        } catch (_: IllegalArgumentException) {
            return DownloadLocatorResult.Failure("Credential reference is invalid")
        }
        val credentials = try {
            credentialStore.get(credentialRef)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DownloadLocatorResult.Failure("Secure credential lookup failed")
        } ?: return DownloadLocatorResult.Failure("Source credentials are unavailable")
        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            return DownloadLocatorResult.Failure("Source credentials are invalid")
        }

        val pathKind = when (download.mediaKind) {
            DownloadMediaKinds.MOVIE -> "movie"
            DownloadMediaKinds.SERIES_EPISODE -> "series"
            else -> return DownloadLocatorResult.Failure("Unsupported download media kind")
        }
        val extension = OfflineDownloadStorage.normalizeExtension(download.containerExtension)
        val baseUrl = server.normalizedUrl.toHttpUrlOrNull()
            ?: return DownloadLocatorResult.Failure("Source server is invalid")
        val resolved = baseUrl.newBuilder()
            .addPathSegment(pathKind)
            .addPathSegment(credentials.username)
            .addPathSegment(credentials.password)
            .addPathSegment("${download.providerStreamId}.$extension")
            .build()
            .toString()
        return DownloadLocatorResult.Success(ResolvedDownloadLocator(resolved))
    }
}
