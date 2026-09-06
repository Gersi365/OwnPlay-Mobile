package app.ownplay.player.playback

import app.ownplay.player.persistence.secure.SensitiveValueRef
import app.ownplay.player.persistence.secure.SensitiveValueStore
import app.ownplay.player.source.CredentialRef
import app.ownplay.player.source.SourceValidator
import app.ownplay.player.source.UrlValidationResult
import app.ownplay.player.source.credential.CredentialStore
import app.ownplay.player.source.credential.XtreamCredentials
import app.ownplay.player.source.m3u.M3uSourceLocatorCodec
import app.ownplay.player.source.xtream.XtreamSourceLocatorCodec
import java.util.Locale
import java.util.concurrent.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class PlaybackResolutionSourceKind {
    XTREAM,
    M3U,
    OTHER,
}

data class PlaybackSourceRecord(
    val sourceId: String,
    val sourceKind: PlaybackResolutionSourceKind,
    val locatorRef: String,
    val credentialRef: String?,
    val enabled: Boolean,
) {
    override fun toString(): String =
        "PlaybackSourceRecord(sourceId=<opaque>, sourceKind=$sourceKind, locatorRef=<opaque>, " +
            "credentialRef=${if (credentialRef == null) "null" else "<opaque>"}, enabled=$enabled)"
}

data class PlaybackChannelRecord(
    val channelId: String,
    val sourceId: String,
    val streamLocatorRef: String,
    val removed: Boolean,
) {
    override fun toString(): String =
        "PlaybackChannelRecord(channelId=<opaque>, sourceId=<opaque>, streamLocatorRef=<opaque>, removed=$removed)"
}

data class PlaybackMovieRecord(
    val movieId: String,
    val sourceId: String,
    val providerStreamId: Int,
    val containerExtension: String?,
) {
    override fun toString(): String =
        "PlaybackMovieRecord(movieId=<opaque>, sourceId=<opaque>, providerStreamId=$providerStreamId, " +
            "containerExtension=$containerExtension)"
}

interface PlaybackResolutionLookup {
    suspend fun sourceById(sourceId: String): PlaybackSourceRecord?
    suspend fun channelById(channelId: String): PlaybackChannelRecord?
    suspend fun movieById(movieId: String): PlaybackMovieRecord?
}

enum class ResolvedPlaybackOrigin {
    DIRECT,
    XTREAM_LIVE,
    XTREAM_VOD,
    XTREAM_SERIES,
    LOCAL_DOWNLOAD,
}

data class ResolvedPlaybackLocator(
    val value: String,
    val origin: ResolvedPlaybackOrigin,
) {
    init {
        require(value.isNotBlank()) { "Resolved playback locator must not be blank" }
    }

    override fun toString(): String =
        "ResolvedPlaybackLocator(value=<redacted>, origin=$origin)"
}

enum class PlaybackResolutionFailureReason {
    SOURCE_NOT_FOUND,
    SOURCE_DISABLED,
    CHANNEL_NOT_FOUND,
    MOVIE_NOT_FOUND,
    SOURCE_CHANNEL_MISMATCH,
    SOURCE_MOVIE_MISMATCH,
    CHANNEL_REMOVED,
    DESCRIPTOR_REFERENCE_INVALID,
    DESCRIPTOR_NOT_FOUND,
    DESCRIPTOR_INVALID,
    SECURE_STORE_FAILURE,
    UNSUPPORTED_SOURCE_KIND,
    SOURCE_LOCATOR_REFERENCE_INVALID,
    SOURCE_LOCATOR_NOT_FOUND,
    SOURCE_LOCATOR_INVALID,
    CREDENTIAL_REFERENCE_MISSING,
    CREDENTIAL_REFERENCE_INVALID,
    CREDENTIALS_NOT_FOUND,
    CREDENTIALS_INVALID,
    CREDENTIAL_STORE_FAILURE,
    CLEARTEXT_NOT_ALLOWED,
    PERSISTENCE_FAILURE,
}

sealed interface PlaybackResolutionResult {
    data class Success(
        val locator: ResolvedPlaybackLocator,
    ) : PlaybackResolutionResult

    data class Failure(
        val reason: PlaybackResolutionFailureReason,
    ) : PlaybackResolutionResult
}

private data class LoadedXtreamSource(
    val normalizedServerUrl: String,
    val usesCleartext: Boolean,
    val allowCleartext: Boolean,
)

private sealed interface LoadedXtreamSourceResult {
    data class Success(val source: LoadedXtreamSource) : LoadedXtreamSourceResult
    data class Failure(val reason: PlaybackResolutionFailureReason) : LoadedXtreamSourceResult
}

private data class LoadedXtreamAccess(
    val source: LoadedXtreamSource,
    val credentials: XtreamCredentials,
)

private sealed interface LoadedXtreamAccessResult {
    data class Success(val access: LoadedXtreamAccess) : LoadedXtreamAccessResult
    data class Failure(val reason: PlaybackResolutionFailureReason) : LoadedXtreamAccessResult
}

private sealed interface LoadedM3uCleartextPolicyResult {
    data class Success(val allowCleartext: Boolean) : LoadedM3uCleartextPolicyResult
    data class Failure(val reason: PlaybackResolutionFailureReason) : LoadedM3uCleartextPolicyResult
}

class LivePlaybackResolver(
    private val lookup: PlaybackResolutionLookup,
    private val sensitiveValueStore: SensitiveValueStore,
    private val credentialStore: CredentialStore,
    private val allowCleartext: Boolean = false,
) {
    suspend fun resolve(request: PlaybackRequest): PlaybackResolutionResult {
        val source = try {
            lookup.sourceById(request.sourceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(PlaybackResolutionFailureReason.PERSISTENCE_FAILURE)
        } ?: return failure(PlaybackResolutionFailureReason.SOURCE_NOT_FOUND)

        if (!source.enabled) {
            return failure(PlaybackResolutionFailureReason.SOURCE_DISABLED)
        }

        return when (request.mediaKind) {
            PlaybackMediaKind.LIVE -> resolveLiveRequest(source, request)
            PlaybackMediaKind.MOVIE -> resolveMovieRequest(source, request)
            PlaybackMediaKind.SERIES_EPISODE -> resolveSeriesEpisodeRequest(source, request)
        }
    }

    private suspend fun resolveLiveRequest(
        source: PlaybackSourceRecord,
        request: PlaybackRequest,
    ): PlaybackResolutionResult {
        val channel = try {
            lookup.channelById(request.channelId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(PlaybackResolutionFailureReason.PERSISTENCE_FAILURE)
        } ?: return failure(PlaybackResolutionFailureReason.CHANNEL_NOT_FOUND)

        if (channel.sourceId != source.sourceId) {
            return failure(PlaybackResolutionFailureReason.SOURCE_CHANNEL_MISMATCH)
        }
        if (channel.removed) {
            return failure(PlaybackResolutionFailureReason.CHANNEL_REMOVED)
        }

        val descriptorRef = sensitiveRef(channel.streamLocatorRef)
            ?: return failure(PlaybackResolutionFailureReason.DESCRIPTOR_REFERENCE_INVALID)
        val descriptor = try {
            sensitiveValueStore.get(descriptorRef)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(PlaybackResolutionFailureReason.SECURE_STORE_FAILURE)
        } ?: return failure(PlaybackResolutionFailureReason.DESCRIPTOR_NOT_FOUND)

        return when (val parsed = PlaybackLocatorParser.parse(descriptor)) {
            is PlaybackLocatorParseResult.Failure -> failure(PlaybackResolutionFailureReason.DESCRIPTOR_INVALID)
            is PlaybackLocatorParseResult.Success -> when (val locator = parsed.locator) {
                is ParsedPlaybackLocator.Direct -> resolveDirect(source, locator.locator)
                is ParsedPlaybackLocator.XtreamLive -> resolveXtreamLive(source, locator.streamId)
            }
        }
    }

    private suspend fun resolveMovieRequest(
        source: PlaybackSourceRecord,
        request: PlaybackRequest,
    ): PlaybackResolutionResult {
        val movie = try {
            lookup.movieById(request.channelId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(PlaybackResolutionFailureReason.PERSISTENCE_FAILURE)
        } ?: return failure(PlaybackResolutionFailureReason.MOVIE_NOT_FOUND)

        if (movie.sourceId != source.sourceId) {
            return failure(PlaybackResolutionFailureReason.SOURCE_MOVIE_MISMATCH)
        }
        if (movie.providerStreamId <= 0) {
            return failure(PlaybackResolutionFailureReason.DESCRIPTOR_INVALID)
        }
        if (source.sourceKind != PlaybackResolutionSourceKind.XTREAM) {
            return failure(PlaybackResolutionFailureReason.UNSUPPORTED_SOURCE_KIND)
        }

        val access = when (val loaded = loadXtreamAccess(source)) {
            is LoadedXtreamAccessResult.Success -> loaded.access
            is LoadedXtreamAccessResult.Failure -> return failure(loaded.reason)
        }
        if (
            access.source.usesCleartext &&
            !allowCleartext &&
            !access.source.allowCleartext
        ) {
            return failure(PlaybackResolutionFailureReason.CLEARTEXT_NOT_ALLOWED)
        }

        val extension = normalizedExtension(movie.containerExtension)
        val baseUrl = access.source.normalizedServerUrl.toHttpUrlOrNull()
            ?: return failure(PlaybackResolutionFailureReason.SOURCE_LOCATOR_INVALID)
        val resolved = baseUrl.newBuilder()
            .addPathSegment("movie")
            .addPathSegment(access.credentials.username)
            .addPathSegment(access.credentials.password)
            .addPathSegment("${movie.providerStreamId}.$extension")
            .build()
            .toString()

        return success(resolved, ResolvedPlaybackOrigin.XTREAM_VOD)
    }

    private suspend fun resolveSeriesEpisodeRequest(
        source: PlaybackSourceRecord,
        request: PlaybackRequest,
    ): PlaybackResolutionResult {
        val streamId = request.providerStreamId
            ?.takeIf { it > 0 }
            ?: return failure(PlaybackResolutionFailureReason.DESCRIPTOR_INVALID)
        if (source.sourceKind != PlaybackResolutionSourceKind.XTREAM) {
            return failure(PlaybackResolutionFailureReason.UNSUPPORTED_SOURCE_KIND)
        }
        val access = when (val loaded = loadXtreamAccess(source)) {
            is LoadedXtreamAccessResult.Success -> loaded.access
            is LoadedXtreamAccessResult.Failure -> return failure(loaded.reason)
        }
        if (
            access.source.usesCleartext &&
            !allowCleartext &&
            !access.source.allowCleartext
        ) {
            return failure(PlaybackResolutionFailureReason.CLEARTEXT_NOT_ALLOWED)
        }
        val extension = normalizedExtension(request.containerExtension)
        val baseUrl = access.source.normalizedServerUrl.toHttpUrlOrNull()
            ?: return failure(PlaybackResolutionFailureReason.SOURCE_LOCATOR_INVALID)
        val resolved = baseUrl.newBuilder()
            .addPathSegment("series")
            .addPathSegment(access.credentials.username)
            .addPathSegment(access.credentials.password)
            .addPathSegment("$streamId.$extension")
            .build()
            .toString()
        return success(resolved, ResolvedPlaybackOrigin.XTREAM_SERIES)
    }

    private suspend fun resolveDirect(
        source: PlaybackSourceRecord,
        locator: String,
    ): PlaybackResolutionResult {
        return when (val validation = SourceValidator.validateRemotePlaylistUrl(locator)) {
            is UrlValidationResult.Invalid -> failure(PlaybackResolutionFailureReason.DESCRIPTOR_INVALID)
            is UrlValidationResult.Valid -> {
                if (validation.usesCleartext && !allowCleartext) {
                    val sourceAllowsCleartext = when (source.sourceKind) {
                        PlaybackResolutionSourceKind.OTHER -> false
                        PlaybackResolutionSourceKind.M3U -> when (
                            val loaded = loadM3uCleartextPolicy(source)
                        ) {
                            is LoadedM3uCleartextPolicyResult.Success -> loaded.allowCleartext
                            is LoadedM3uCleartextPolicyResult.Failure -> return failure(loaded.reason)
                        }
                        PlaybackResolutionSourceKind.XTREAM -> when (
                            val loaded = loadXtreamSource(source)
                        ) {
                            is LoadedXtreamSourceResult.Success -> loaded.source.allowCleartext
                            is LoadedXtreamSourceResult.Failure -> return failure(loaded.reason)
                        }
                    }
                    if (!sourceAllowsCleartext) {
                        return failure(PlaybackResolutionFailureReason.CLEARTEXT_NOT_ALLOWED)
                    }
                }
                success(validation.normalizedUrl, ResolvedPlaybackOrigin.DIRECT)
            }
        }
    }

    private suspend fun resolveXtreamLive(
        source: PlaybackSourceRecord,
        streamId: Int,
    ): PlaybackResolutionResult {
        if (source.sourceKind != PlaybackResolutionSourceKind.XTREAM) {
            return failure(PlaybackResolutionFailureReason.UNSUPPORTED_SOURCE_KIND)
        }

        val access = when (val loaded = loadXtreamAccess(source)) {
            is LoadedXtreamAccessResult.Success -> loaded.access
            is LoadedXtreamAccessResult.Failure -> return failure(loaded.reason)
        }
        if (
            access.source.usesCleartext &&
            !allowCleartext &&
            !access.source.allowCleartext
        ) {
            return failure(PlaybackResolutionFailureReason.CLEARTEXT_NOT_ALLOWED)
        }

        val baseUrl = access.source.normalizedServerUrl.toHttpUrlOrNull()
            ?: return failure(PlaybackResolutionFailureReason.SOURCE_LOCATOR_INVALID)
        val resolved = baseUrl.newBuilder()
            .addPathSegment("live")
            .addPathSegment(access.credentials.username)
            .addPathSegment(access.credentials.password)
            .addPathSegment("$streamId.ts")
            .build()
            .toString()

        return success(resolved, ResolvedPlaybackOrigin.XTREAM_LIVE)
    }

    private suspend fun loadXtreamAccess(
        source: PlaybackSourceRecord,
    ): LoadedXtreamAccessResult {
        val loadedSource = when (val loaded = loadXtreamSource(source)) {
            is LoadedXtreamSourceResult.Success -> loaded.source
            is LoadedXtreamSourceResult.Failure -> return LoadedXtreamAccessResult.Failure(loaded.reason)
        }
        val credentialRefValue = source.credentialRef
            ?: return LoadedXtreamAccessResult.Failure(
                PlaybackResolutionFailureReason.CREDENTIAL_REFERENCE_MISSING,
            )
        val credentialRef = try {
            CredentialRef(credentialRefValue)
        } catch (_: IllegalArgumentException) {
            return LoadedXtreamAccessResult.Failure(
                PlaybackResolutionFailureReason.CREDENTIAL_REFERENCE_INVALID,
            )
        }
        val credentials = try {
            credentialStore.get(credentialRef)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LoadedXtreamAccessResult.Failure(
                PlaybackResolutionFailureReason.CREDENTIAL_STORE_FAILURE,
            )
        } ?: return LoadedXtreamAccessResult.Failure(
            PlaybackResolutionFailureReason.CREDENTIALS_NOT_FOUND,
        )

        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            return LoadedXtreamAccessResult.Failure(
                PlaybackResolutionFailureReason.CREDENTIALS_INVALID,
            )
        }

        return LoadedXtreamAccessResult.Success(
            LoadedXtreamAccess(
                source = loadedSource,
                credentials = credentials,
            ),
        )
    }

    private suspend fun loadM3uCleartextPolicy(
        source: PlaybackSourceRecord,
    ): LoadedM3uCleartextPolicyResult {
        if (source.sourceKind != PlaybackResolutionSourceKind.M3U) {
            return LoadedM3uCleartextPolicyResult.Failure(
                PlaybackResolutionFailureReason.UNSUPPORTED_SOURCE_KIND,
            )
        }
        val sourceLocatorRef = sensitiveRef(source.locatorRef)
            ?: return LoadedM3uCleartextPolicyResult.Failure(
                PlaybackResolutionFailureReason.SOURCE_LOCATOR_REFERENCE_INVALID,
            )
        val storedLocator = try {
            sensitiveValueStore.get(sourceLocatorRef)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LoadedM3uCleartextPolicyResult.Failure(
                PlaybackResolutionFailureReason.SECURE_STORE_FAILURE,
            )
        } ?: return LoadedM3uCleartextPolicyResult.Failure(
            PlaybackResolutionFailureReason.SOURCE_LOCATOR_NOT_FOUND,
        )
        val locator = M3uSourceLocatorCodec.parseOrLegacy(storedLocator)
        return LoadedM3uCleartextPolicyResult.Success(locator.allowCleartext)
    }

    private suspend fun loadXtreamSource(
        source: PlaybackSourceRecord,
    ): LoadedXtreamSourceResult {
        if (source.sourceKind != PlaybackResolutionSourceKind.XTREAM) {
            return LoadedXtreamSourceResult.Failure(
                PlaybackResolutionFailureReason.UNSUPPORTED_SOURCE_KIND,
            )
        }
        val sourceLocatorRef = sensitiveRef(source.locatorRef)
            ?: return LoadedXtreamSourceResult.Failure(
                PlaybackResolutionFailureReason.SOURCE_LOCATOR_REFERENCE_INVALID,
            )
        val storedLocator = try {
            sensitiveValueStore.get(sourceLocatorRef)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LoadedXtreamSourceResult.Failure(
                PlaybackResolutionFailureReason.SECURE_STORE_FAILURE,
            )
        } ?: return LoadedXtreamSourceResult.Failure(
            PlaybackResolutionFailureReason.SOURCE_LOCATOR_NOT_FOUND,
        )

        val sourceLocator = XtreamSourceLocatorCodec.parse(storedLocator)
            ?: return LoadedXtreamSourceResult.Failure(
                PlaybackResolutionFailureReason.SOURCE_LOCATOR_INVALID,
            )
        val validatedServer = when (
            val validation = SourceValidator.validateXtreamServer(sourceLocator.serverUrl)
        ) {
            is UrlValidationResult.Invalid -> {
                return LoadedXtreamSourceResult.Failure(
                    PlaybackResolutionFailureReason.SOURCE_LOCATOR_INVALID,
                )
            }
            is UrlValidationResult.Valid -> validation
        }

        return LoadedXtreamSourceResult.Success(
            LoadedXtreamSource(
                normalizedServerUrl = validatedServer.normalizedUrl,
                usesCleartext = validatedServer.usesCleartext,
                allowCleartext = sourceLocator.allowCleartext,
            ),
        )
    }

    private fun normalizedExtension(value: String?): String = value
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { candidate -> candidate.matches(Regex("[a-z0-9]{1,8}")) }
        ?: "mp4"

    private fun sensitiveRef(value: String): SensitiveValueRef? = try {
        SensitiveValueRef(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun success(
        value: String,
        origin: ResolvedPlaybackOrigin,
    ): PlaybackResolutionResult = PlaybackResolutionResult.Success(
        ResolvedPlaybackLocator(value = value, origin = origin),
    )

    private fun failure(reason: PlaybackResolutionFailureReason): PlaybackResolutionResult =
        PlaybackResolutionResult.Failure(reason)
}
