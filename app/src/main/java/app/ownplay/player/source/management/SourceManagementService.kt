package app.ownplay.player.source.management

import androidx.room.withTransaction
import app.ownplay.player.persistence.OwnPlayDatabase
import app.ownplay.player.persistence.SourceKinds
import app.ownplay.player.persistence.secure.SensitiveValueRef
import app.ownplay.player.persistence.secure.SensitiveValueStore
import app.ownplay.player.source.CredentialRef
import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceResult
import app.ownplay.player.source.SourceValidator
import app.ownplay.player.source.UrlValidationResult
import app.ownplay.player.source.credential.CredentialStore
import app.ownplay.player.source.credential.XtreamCredentials
import app.ownplay.player.source.m3u.M3uSourceLocatorCodec
import app.ownplay.player.source.xtream.XtreamClient
import app.ownplay.player.source.xtream.XtreamSourceLocator
import app.ownplay.player.source.xtream.XtreamSourceLocatorCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Editable source metadata that is safe to expose to the source-management UI.
 * Credentials are deliberately not returned.
 */
data class SourceEditSnapshot(
    val sourceId: String,
    val name: String,
    val sourceKind: String,
    val endpoint: String?,
    val allowCleartext: Boolean,
)

sealed interface SourceMutationResult {
    data object Success : SourceMutationResult

    data class Failure(
        val reason: SourceMutationFailure,
    ) : SourceMutationResult
}

sealed interface SourceMutationFailure {
    data object NotFound : SourceMutationFailure
    data object InvalidName : SourceMutationFailure
    data object UnsupportedEdit : SourceMutationFailure
    data object IncompleteCredentialReplacement : SourceMutationFailure
    data object SecureStorageFailure : SourceMutationFailure
    data object PersistenceFailure : SourceMutationFailure
    data class SourceFailure(val error: SourceError) : SourceMutationFailure
}

class SourceManagementService(
    private val database: OwnPlayDatabase,
    private val sensitiveValueStore: SensitiveValueStore,
    private val credentialStore: CredentialStore,
    private val xtreamClient: XtreamClient = XtreamClient(),
) {
    suspend fun load(sourceId: String): SourceEditSnapshot? = withContext(Dispatchers.IO) {
        val source = database.playlistSourceDao().getById(sourceId) ?: return@withContext null
        val locatorValue = try {
            sensitiveValueStore.get(SensitiveValueRef(source.locatorRef))
        } catch (error: Exception) {
            error.rethrowCancellation()
            null
        }

        when (source.sourceKind) {
            SourceKinds.XTREAM -> {
                val locator = locatorValue?.let(XtreamSourceLocatorCodec::parse)
                SourceEditSnapshot(
                    sourceId = source.sourceId,
                    name = source.name,
                    sourceKind = source.sourceKind,
                    endpoint = locator?.serverUrl,
                    allowCleartext = locator?.allowCleartext == true,
                )
            }

            SourceKinds.REMOTE_M3U -> {
                val locator = locatorValue?.let(M3uSourceLocatorCodec::parseOrLegacy)
                SourceEditSnapshot(
                    sourceId = source.sourceId,
                    name = source.name,
                    sourceKind = source.sourceKind,
                    endpoint = locator?.endpoint,
                    allowCleartext = locator?.allowCleartext == true,
                )
            }

            SourceKinds.LOCAL_M3U -> {
                val locator = locatorValue?.let(M3uSourceLocatorCodec::parseOrLegacy)
                SourceEditSnapshot(
                    sourceId = source.sourceId,
                    name = source.name,
                    sourceKind = source.sourceKind,
                    endpoint = null,
                    allowCleartext = locator?.allowCleartext == true,
                )
            }

            else -> SourceEditSnapshot(
                sourceId = source.sourceId,
                name = source.name,
                sourceKind = source.sourceKind,
                endpoint = null,
                allowCleartext = false,
            )
        }
    }

    suspend fun rename(
        sourceId: String,
        name: String,
    ): SourceMutationResult = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            return@withContext SourceMutationResult.Failure(SourceMutationFailure.InvalidName)
        }
        val source = database.playlistSourceDao().getById(sourceId)
            ?: return@withContext SourceMutationResult.Failure(SourceMutationFailure.NotFound)
        try {
            database.withTransaction {
                database.playlistSourceDao().upsert(
                    source.copy(
                        name = normalizedName,
                        updatedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            }
            SourceMutationResult.Success
        } catch (error: Exception) {
            error.rethrowCancellation()
            SourceMutationResult.Failure(SourceMutationFailure.PersistenceFailure)
        }
    }

    suspend fun updateXtream(
        sourceId: String,
        name: String,
        serverUrl: String,
        replacementUsername: String,
        replacementPassword: String,
        allowCleartext: Boolean,
    ): SourceMutationResult = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            return@withContext SourceMutationResult.Failure(SourceMutationFailure.InvalidName)
        }

        val source = database.playlistSourceDao().getById(sourceId)
            ?: return@withContext SourceMutationResult.Failure(SourceMutationFailure.NotFound)
        if (source.sourceKind != SourceKinds.XTREAM) {
            return@withContext SourceMutationResult.Failure(SourceMutationFailure.UnsupportedEdit)
        }

        val validatedServer = when (val validation = SourceValidator.validateXtreamServer(serverUrl)) {
            is UrlValidationResult.Invalid -> {
                return@withContext SourceMutationResult.Failure(
                    SourceMutationFailure.SourceFailure(validation.error),
                )
            }
            is UrlValidationResult.Valid -> validation
        }
        if (validatedServer.usesCleartext && !allowCleartext) {
            return@withContext SourceMutationResult.Failure(
                SourceMutationFailure.SourceFailure(
                    SourceError.CleartextTransportRequiresOptIn,
                ),
            )
        }

        val credentialEditMode = XtreamCredentialReplacementPolicy.classify(
            username = replacementUsername,
            password = replacementPassword,
        )
        if (credentialEditMode == XtreamCredentialEditMode.INCOMPLETE) {
            return@withContext SourceMutationResult.Failure(
                SourceMutationFailure.IncompleteCredentialReplacement,
            )
        }

        val oldCredentialRef = source.credentialRef?.let(::CredentialRef)
        val replacingCredentials = credentialEditMode == XtreamCredentialEditMode.REPLACE
        val effectiveCredentials = if (replacingCredentials) {
            XtreamCredentials(
                username = replacementUsername.trim(),
                password = replacementPassword,
            )
        } else {
            val existingRef = oldCredentialRef
                ?: return@withContext SourceMutationResult.Failure(
                    SourceMutationFailure.SecureStorageFailure,
                )
            try {
                credentialStore.get(existingRef)
            } catch (error: Exception) {
                error.rethrowCancellation()
                null
            } ?: return@withContext SourceMutationResult.Failure(
                SourceMutationFailure.SecureStorageFailure,
            )
        }

        when (
            val validation = xtreamClient.validateAccount(
                serverUrl = validatedServer.normalizedUrl,
                credentials = effectiveCredentials,
                allowCleartext = allowCleartext,
            )
        ) {
            is SourceResult.Success -> Unit
            is SourceResult.Failure -> {
                return@withContext SourceMutationResult.Failure(
                    SourceMutationFailure.SourceFailure(validation.error),
                )
            }
        }

        val newLocatorRef = try {
            sensitiveValueStore.put(
                XtreamSourceLocatorCodec.encode(
                    XtreamSourceLocator(
                        serverUrl = validatedServer.normalizedUrl,
                        allowCleartext = allowCleartext,
                    ),
                ),
            )
        } catch (error: Exception) {
            error.rethrowCancellation()
            return@withContext SourceMutationResult.Failure(
                SourceMutationFailure.SecureStorageFailure,
            )
        }

        val newCredentialRef = if (replacingCredentials) {
            try {
                credentialStore.put(effectiveCredentials)
            } catch (error: Exception) {
                runCatching { sensitiveValueStore.delete(newLocatorRef) }
                error.rethrowCancellation()
                return@withContext SourceMutationResult.Failure(
                    SourceMutationFailure.SecureStorageFailure,
                )
            }
        } else {
            requireNotNull(oldCredentialRef)
        }

        try {
            database.withTransaction {
                database.playlistSourceDao().upsert(
                    source.copy(
                        name = normalizedName,
                        locatorRef = newLocatorRef.value,
                        credentialRef = newCredentialRef.value,
                        updatedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            }
        } catch (error: Exception) {
            runCatching { sensitiveValueStore.delete(newLocatorRef) }
            if (replacingCredentials) {
                runCatching { credentialStore.delete(newCredentialRef) }
            }
            error.rethrowCancellation()
            return@withContext SourceMutationResult.Failure(
                SourceMutationFailure.PersistenceFailure,
            )
        }

        runCatching { sensitiveValueStore.delete(SensitiveValueRef(source.locatorRef)) }
        if (replacingCredentials) {
            oldCredentialRef?.let { oldRef ->
                runCatching { credentialStore.delete(oldRef) }
            }
        }
        SourceMutationResult.Success
    }

    suspend fun delete(sourceId: String): SourceMutationResult = withContext(Dispatchers.IO) {
        val source = database.playlistSourceDao().getById(sourceId)
            ?: return@withContext SourceMutationResult.Failure(SourceMutationFailure.NotFound)

        val channels = runCatching {
            database.providerCatalogDao().channelsForSource(sourceId)
        }.getOrElse { error ->
            error.rethrowCancellation()
            return@withContext SourceMutationResult.Failure(
                SourceMutationFailure.PersistenceFailure,
            )
        }
        val customizations = runCatching {
            database.personalizationDao().customizationsForSource(sourceId)
        }.getOrElse { error ->
            error.rethrowCancellation()
            return@withContext SourceMutationResult.Failure(
                SourceMutationFailure.PersistenceFailure,
            )
        }

        val sensitiveRefs = buildSet {
            add(SensitiveValueRef(source.locatorRef))
            channels.forEach { channel ->
                add(SensitiveValueRef(channel.streamLocatorRef))
                channel.logoRef?.let { add(SensitiveValueRef(it)) }
            }
            customizations.forEach { customization ->
                customization.logoOverrideRef?.let { add(SensitiveValueRef(it)) }
            }
        }

        try {
            database.withTransaction {
                val deleted = database.playlistSourceDao().deleteById(sourceId)
                check(deleted > 0) { "Source disappeared during delete transaction" }
            }
        } catch (error: Exception) {
            error.rethrowCancellation()
            return@withContext SourceMutationResult.Failure(
                SourceMutationFailure.PersistenceFailure,
            )
        }

        runCatching { sensitiveValueStore.deleteAll(sensitiveRefs) }
        source.credentialRef?.let { value ->
            runCatching { credentialStore.delete(CredentialRef(value)) }
        }
        SourceMutationResult.Success
    }
}

private fun Throwable.rethrowCancellation() {
    if (this is CancellationException) throw this
}
