package app.ownplay.player.source.onboarding

import androidx.room.withTransaction
import app.ownplay.player.persistence.OwnPlayDatabase
import app.ownplay.player.persistence.PlaylistRefreshStateEntity
import app.ownplay.player.persistence.PlaylistSourceEntity
import app.ownplay.player.persistence.RefreshStates
import app.ownplay.player.persistence.SourceKinds
import app.ownplay.player.persistence.secure.SensitiveValueRef
import app.ownplay.player.persistence.secure.SensitiveValueStore
import app.ownplay.player.source.CredentialRef
import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceValidator
import app.ownplay.player.source.UrlValidationResult
import app.ownplay.player.source.credential.CredentialStore
import app.ownplay.player.source.credential.XtreamCredentials
import app.ownplay.player.source.m3u.M3uSourceLocator
import app.ownplay.player.source.m3u.M3uSourceLocatorCodec
import app.ownplay.player.source.xtream.XtreamSourceLocator
import app.ownplay.player.source.xtream.XtreamSourceLocatorCodec
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Persists a playlist identity and secure locator before any network/catalog work starts.
 *
 * A staged source is disabled until its first live catalog import succeeds. That gives every
 * submission a stable sourceId immediately, makes Configured playlists deterministic, and lets
 * the runtime resume an interrupted import on the next app start.
 */
class SourceStagingService(
    private val database: OwnPlayDatabase,
    private val sensitiveValueStore: SensitiveValueStore,
    private val credentialStore: CredentialStore,
) {
    suspend fun stageXtream(
        name: String,
        serverUrl: String,
        username: String,
        password: String,
        allowCleartext: Boolean,
    ): SourceOnboardingResult {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            return SourceOnboardingResult.Failure(SourceOnboardingFailure.InvalidName)
        }

        val validated = when (val validation = SourceValidator.validateXtreamServer(serverUrl)) {
            is UrlValidationResult.Invalid -> {
                return SourceOnboardingResult.Failure(
                    SourceOnboardingFailure.SourceFailure(validation.error),
                )
            }
            is UrlValidationResult.Valid -> validation
        }
        if (validated.usesCleartext && !allowCleartext) {
            return SourceOnboardingResult.Failure(
                SourceOnboardingFailure.SourceFailure(
                    SourceError.CleartextTransportRequiresOptIn,
                ),
            )
        }
        if (username.trim().isEmpty() || password.isEmpty()) {
            return SourceOnboardingResult.Failure(
                SourceOnboardingFailure.SourceFailure(SourceError.InvalidCredentials),
            )
        }

        val credentialRef = try {
            credentialStore.put(
                XtreamCredentials(
                    username = username.trim(),
                    password = password,
                ),
            )
        } catch (error: Exception) {
            error.rethrowCancellation()
            return SourceOnboardingResult.Failure(SourceOnboardingFailure.SecureStorageFailure)
        }

        val result = persistPending(
            name = normalizedName,
            sourceKind = SourceKinds.XTREAM,
            locatorValue = XtreamSourceLocatorCodec.encode(
                XtreamSourceLocator(
                    serverUrl = validated.normalizedUrl,
                    allowCleartext = allowCleartext,
                ),
            ),
            credentialRef = credentialRef,
        )
        if (result is SourceOnboardingResult.Failure) {
            runCatching { credentialStore.delete(credentialRef) }
        }
        return result
    }

    suspend fun stageRemoteM3u(
        name: String,
        playlistUrl: String,
        allowCleartext: Boolean,
    ): SourceOnboardingResult {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            return SourceOnboardingResult.Failure(SourceOnboardingFailure.InvalidName)
        }

        val validated = when (val validation = SourceValidator.validateRemotePlaylistUrl(playlistUrl)) {
            is UrlValidationResult.Invalid -> {
                return SourceOnboardingResult.Failure(
                    SourceOnboardingFailure.SourceFailure(validation.error),
                )
            }
            is UrlValidationResult.Valid -> validation
        }
        if (validated.usesCleartext && !allowCleartext) {
            return SourceOnboardingResult.Failure(
                SourceOnboardingFailure.SourceFailure(
                    SourceError.CleartextTransportRequiresOptIn,
                ),
            )
        }

        return persistPending(
            name = normalizedName,
            sourceKind = SourceKinds.REMOTE_M3U,
            locatorValue = M3uSourceLocatorCodec.encode(
                M3uSourceLocator(
                    endpoint = validated.normalizedUrl,
                    allowCleartext = allowCleartext,
                    epgUrls = emptyList(),
                ),
            ),
            credentialRef = null,
        )
    }

    suspend fun stageLocalM3u(
        name: String,
        documentUri: String,
        allowCleartext: Boolean,
    ): SourceOnboardingResult {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            return SourceOnboardingResult.Failure(SourceOnboardingFailure.InvalidName)
        }
        val normalizedUri = documentUri.trim()
        SourceValidator.validateLocalDocumentUri(normalizedUri)?.let { error ->
            return SourceOnboardingResult.Failure(SourceOnboardingFailure.SourceFailure(error))
        }

        return persistPending(
            name = normalizedName,
            sourceKind = SourceKinds.LOCAL_M3U,
            locatorValue = M3uSourceLocatorCodec.encode(
                M3uSourceLocator(
                    endpoint = normalizedUri,
                    allowCleartext = allowCleartext,
                    epgUrls = emptyList(),
                ),
            ),
            credentialRef = null,
        )
    }

    private suspend fun persistPending(
        name: String,
        sourceKind: String,
        locatorValue: String,
        credentialRef: CredentialRef?,
    ): SourceOnboardingResult {
        val locatorRef = try {
            sensitiveValueStore.put(locatorValue)
        } catch (error: Exception) {
            error.rethrowCancellation()
            return SourceOnboardingResult.Failure(SourceOnboardingFailure.SecureStorageFailure)
        }

        val sourceId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val source = PlaylistSourceEntity(
            sourceId = sourceId,
            name = name,
            sourceKind = sourceKind,
            locatorRef = locatorRef.value,
            credentialRef = credentialRef?.value,
            enabled = false,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )

        try {
            database.withTransaction {
                database.playlistSourceDao().upsert(source)
                database.refreshStateDao().upsert(
                    PlaylistRefreshStateEntity(
                        sourceId = sourceId,
                        generation = now,
                        state = RefreshStates.RUNNING,
                        lastAttemptAtEpochMillis = now,
                        lastSuccessAtEpochMillis = null,
                        lastErrorCode = null,
                    ),
                )
            }
        } catch (error: Exception) {
            runCatching { sensitiveValueStore.delete(SensitiveValueRef(locatorRef.value)) }
            error.rethrowCancellation()
            return SourceOnboardingResult.Failure(SourceOnboardingFailure.PersistenceFailure)
        }

        return SourceOnboardingResult.Success(
            sourceId = sourceId,
            channelCount = 0,
        )
    }
}

private fun Exception.rethrowCancellation() {
    if (this is CancellationException) throw this
}
