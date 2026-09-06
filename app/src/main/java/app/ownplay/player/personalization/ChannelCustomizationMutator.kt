package app.ownplay.player.personalization

import androidx.room.withTransaction
import app.ownplay.player.persistence.OwnPlayDatabase
import app.ownplay.player.persistence.secure.SensitiveValueRef
import app.ownplay.player.persistence.secure.SensitiveValueStore
import kotlinx.coroutines.CancellationException

enum class ChannelCustomizationFailureReason {
    INVALID_SOURCE_ID,
    INVALID_CHANNEL_ID,
    INVALID_LOCAL_NAME,
    INVALID_LOGO_VALUE,
    CHANNEL_NOT_FOUND,
    SECURE_STORAGE_FAILURE,
    PERSISTENCE_FAILURE,
}

sealed interface ChannelCustomizationMutationResult {
    data class Success(val channelId: String) : ChannelCustomizationMutationResult

    data class Failure(
        val reason: ChannelCustomizationFailureReason,
        val channelId: String? = null,
    ) : ChannelCustomizationMutationResult
}

class ChannelCustomizationMutator(
    private val database: OwnPlayDatabase,
    private val sensitiveValueStore: SensitiveValueStore,
) {
    suspend fun setLocalDisplayName(
        sourceId: String,
        channelId: String,
        localDisplayName: String,
    ): ChannelCustomizationMutationResult {
        val normalized = localDisplayName.trim()
        if (normalized.isEmpty()) {
            return ChannelCustomizationMutationResult.Failure(
                reason = ChannelCustomizationFailureReason.INVALID_LOCAL_NAME,
                channelId = channelId.takeIf(String::isNotBlank),
            )
        }
        return updateCustomization(sourceId, channelId) { existing ->
            ChannelCustomizationPatcher.withLocalDisplayName(
                existing = existing,
                channelId = channelId,
                localDisplayName = normalized,
            )
        }
    }

    suspend fun clearLocalDisplayName(
        sourceId: String,
        channelId: String,
    ): ChannelCustomizationMutationResult = updateCustomization(sourceId, channelId) { existing ->
        ChannelCustomizationPatcher.withLocalDisplayName(
            existing = existing,
            channelId = channelId,
            localDisplayName = null,
        )
    }

    suspend fun setLogoOverride(
        sourceId: String,
        channelId: String,
        logoValue: String,
    ): ChannelCustomizationMutationResult {
        if (sourceId.isBlank()) return invalidSource()
        if (channelId.isBlank()) return invalidChannel()
        if (logoValue.isBlank()) {
            return ChannelCustomizationMutationResult.Failure(
                reason = ChannelCustomizationFailureReason.INVALID_LOGO_VALUE,
                channelId = channelId,
            )
        }

        val exists = try {
            database.personalizationDao().channelExistsInSource(sourceId, channelId)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return persistenceFailure(channelId)
        }
        if (!exists) return channelNotFound(channelId)

        val newRef = try {
            sensitiveValueStore.put(logoValue)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return ChannelCustomizationMutationResult.Failure(
                reason = ChannelCustomizationFailureReason.SECURE_STORAGE_FAILURE,
                channelId = channelId,
            )
        }

        var oldRef: SensitiveValueRef? = null
        val result = try {
            database.withTransaction {
                val dao = database.personalizationDao()
                if (!dao.channelExistsInSource(sourceId, channelId)) {
                    return@withTransaction channelNotFound(channelId)
                }
                val existing = dao.customizationForChannel(sourceId, channelId)
                oldRef = existing?.logoOverrideRef?.let(::SensitiveValueRef)
                dao.upsertCustomization(
                    ChannelCustomizationPatcher.withLogoOverrideRef(
                        existing = existing,
                        channelId = channelId,
                        logoOverrideRef = newRef.value,
                    ),
                )
                ChannelCustomizationMutationResult.Success(channelId)
            }
        } catch (error: Exception) {
            if (error is CancellationException) {
                cleanup(newRef)
                throw error
            }
            cleanup(newRef)
            return persistenceFailure(channelId)
        }

        if (result is ChannelCustomizationMutationResult.Failure) {
            cleanup(newRef)
            return result
        }
        oldRef?.takeIf { it != newRef }?.let(::cleanup)
        return result
    }

    suspend fun clearLogoOverride(
        sourceId: String,
        channelId: String,
    ): ChannelCustomizationMutationResult {
        if (sourceId.isBlank()) return invalidSource()
        if (channelId.isBlank()) return invalidChannel()

        var oldRef: SensitiveValueRef? = null
        val result = try {
            database.withTransaction {
                val dao = database.personalizationDao()
                if (!dao.channelExistsInSource(sourceId, channelId)) {
                    return@withTransaction channelNotFound(channelId)
                }
                val existing = dao.customizationForChannel(sourceId, channelId)
                oldRef = existing?.logoOverrideRef?.let(::SensitiveValueRef)
                dao.upsertCustomization(
                    ChannelCustomizationPatcher.withLogoOverrideRef(
                        existing = existing,
                        channelId = channelId,
                        logoOverrideRef = null,
                    ),
                )
                ChannelCustomizationMutationResult.Success(channelId)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return persistenceFailure(channelId)
        }

        if (result is ChannelCustomizationMutationResult.Success) {
            oldRef?.let(::cleanup)
        }
        return result
    }

    private suspend fun updateCustomization(
        sourceId: String,
        channelId: String,
        patch: (app.ownplay.player.persistence.ChannelCustomizationEntity?) ->
            app.ownplay.player.persistence.ChannelCustomizationEntity,
    ): ChannelCustomizationMutationResult {
        if (sourceId.isBlank()) return invalidSource()
        if (channelId.isBlank()) return invalidChannel()
        return try {
            database.withTransaction {
                val dao = database.personalizationDao()
                if (!dao.channelExistsInSource(sourceId, channelId)) {
                    return@withTransaction channelNotFound(channelId)
                }
                val existing = dao.customizationForChannel(sourceId, channelId)
                val updated = patch(existing)
                dao.upsertCustomization(updated)
                ChannelCustomizationMutationResult.Success(channelId)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            persistenceFailure(channelId)
        }
    }

    private fun cleanup(ref: SensitiveValueRef) {
        runCatching { sensitiveValueStore.delete(ref) }
    }

    private fun invalidSource() = ChannelCustomizationMutationResult.Failure(
        reason = ChannelCustomizationFailureReason.INVALID_SOURCE_ID,
    )

    private fun invalidChannel() = ChannelCustomizationMutationResult.Failure(
        reason = ChannelCustomizationFailureReason.INVALID_CHANNEL_ID,
    )

    private fun channelNotFound(channelId: String) = ChannelCustomizationMutationResult.Failure(
        reason = ChannelCustomizationFailureReason.CHANNEL_NOT_FOUND,
        channelId = channelId,
    )

    private fun persistenceFailure(channelId: String) = ChannelCustomizationMutationResult.Failure(
        reason = ChannelCustomizationFailureReason.PERSISTENCE_FAILURE,
        channelId = channelId,
    )
}
