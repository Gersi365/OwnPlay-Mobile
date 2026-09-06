package app.ownplay.player.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal const val PERSONALIZATION_BACKUP_FORMAT = "ownplay.personalization"
internal const val PERSONALIZATION_BACKUP_VERSION = 1

data class BackupChannelIdentity(
    val providerKey: String,
    val sourceKind: String,
    val sourceName: String,
    val sourceId: String? = null,
)

data class BackupChannelRecord(
    val identity: BackupChannelIdentity,
    val localDisplayName: String? = null,
    val manualOrder: Long? = null,
    val hiddenAtEpochMillis: Long? = null,
    val favoriteOrder: Long? = null,
    val favoriteAddedAtEpochMillis: Long? = null,
    val logoOverrideOmitted: Boolean = false,
)

data class BackupGroupMember(
    val identity: BackupChannelIdentity,
    val groupOrder: Long,
)

data class BackupGroupRecord(
    val groupId: String,
    val name: String,
    val groupOrder: Long,
    val createdAtEpochMillis: Long,
    val members: List<BackupGroupMember>,
)

data class PersonalizationBackupV1(
    val createdAtEpochMillis: Long,
    val channels: List<BackupChannelRecord>,
    val groups: List<BackupGroupRecord>,
)

enum class BackupDecodeFailureReason {
    INVALID_JSON,
    UNSUPPORTED_FORMAT,
    UNSUPPORTED_VERSION,
    INVALID_PAYLOAD,
}

sealed interface BackupDecodeResult {
    data class Success(val backup: PersonalizationBackupV1) : BackupDecodeResult

    data class Failure(val reason: BackupDecodeFailureReason) : BackupDecodeResult
}

object PersonalizationBackupCodec {
    fun encode(backup: PersonalizationBackupV1): String {
        validate(backup)
        return buildJsonObject {
            put("format", JsonPrimitive(PERSONALIZATION_BACKUP_FORMAT))
            put("version", JsonPrimitive(PERSONALIZATION_BACKUP_VERSION))
            put("createdAtEpochMillis", JsonPrimitive(backup.createdAtEpochMillis))
            put("channels", buildJsonArray {
                backup.channels.forEach { record -> add(encodeChannelRecord(record)) }
            })
            put("groups", buildJsonArray {
                backup.groups.forEach { group -> add(encodeGroup(group)) }
            })
        }.toString()
    }

    fun decode(raw: String): BackupDecodeResult {
        val root = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return BackupDecodeResult.Failure(BackupDecodeFailureReason.INVALID_JSON)
        }
        val format = root["format"]?.jsonPrimitive?.contentOrNull
            ?: return BackupDecodeResult.Failure(BackupDecodeFailureReason.INVALID_PAYLOAD)
        if (format != PERSONALIZATION_BACKUP_FORMAT) {
            return BackupDecodeResult.Failure(BackupDecodeFailureReason.UNSUPPORTED_FORMAT)
        }
        val version = runCatching { root.getValue("version").jsonPrimitive.long }.getOrNull()
            ?: return BackupDecodeResult.Failure(BackupDecodeFailureReason.INVALID_PAYLOAD)
        if (version != PERSONALIZATION_BACKUP_VERSION.toLong()) {
            return BackupDecodeResult.Failure(BackupDecodeFailureReason.UNSUPPORTED_VERSION)
        }

        return try {
            val backup = PersonalizationBackupV1(
                createdAtEpochMillis = root.requiredLong("createdAtEpochMillis"),
                channels = root.requiredArray("channels").map { element ->
                    decodeChannelRecord(element.jsonObject)
                },
                groups = root.requiredArray("groups").map { element ->
                    decodeGroup(element.jsonObject)
                },
            )
            validate(backup)
            BackupDecodeResult.Success(backup)
        } catch (_: Exception) {
            BackupDecodeResult.Failure(BackupDecodeFailureReason.INVALID_PAYLOAD)
        }
    }

    private fun encodeChannelRecord(record: BackupChannelRecord): JsonObject = buildJsonObject {
        put("identity", encodeIdentity(record.identity))
        putNullableString("localDisplayName", record.localDisplayName)
        putNullableLong("manualOrder", record.manualOrder)
        putNullableLong("hiddenAtEpochMillis", record.hiddenAtEpochMillis)
        putNullableLong("favoriteOrder", record.favoriteOrder)
        putNullableLong("favoriteAddedAtEpochMillis", record.favoriteAddedAtEpochMillis)
        put("logoOverrideOmitted", JsonPrimitive(record.logoOverrideOmitted))
    }

    private fun encodeGroup(group: BackupGroupRecord): JsonObject = buildJsonObject {
        put("groupId", JsonPrimitive(group.groupId))
        put("name", JsonPrimitive(group.name))
        put("groupOrder", JsonPrimitive(group.groupOrder))
        put("createdAtEpochMillis", JsonPrimitive(group.createdAtEpochMillis))
        put("members", buildJsonArray {
            group.members.forEach { member ->
                add(buildJsonObject {
                    put("identity", encodeIdentity(member.identity))
                    put("groupOrder", JsonPrimitive(member.groupOrder))
                })
            }
        })
    }

    private fun encodeIdentity(identity: BackupChannelIdentity): JsonObject = buildJsonObject {
        put("providerKey", JsonPrimitive(identity.providerKey))
        put("sourceKind", JsonPrimitive(identity.sourceKind))
        put("sourceName", JsonPrimitive(identity.sourceName))
        putNullableString("sourceId", identity.sourceId)
    }

    private fun decodeChannelRecord(value: JsonObject): BackupChannelRecord = BackupChannelRecord(
        identity = decodeIdentity(value.requiredObject("identity")),
        localDisplayName = value.optionalString("localDisplayName"),
        manualOrder = value.optionalLong("manualOrder"),
        hiddenAtEpochMillis = value.optionalLong("hiddenAtEpochMillis"),
        favoriteOrder = value.optionalLong("favoriteOrder"),
        favoriteAddedAtEpochMillis = value.optionalLong("favoriteAddedAtEpochMillis"),
        logoOverrideOmitted = value.optionalBoolean("logoOverrideOmitted") ?: false,
    )

    private fun decodeGroup(value: JsonObject): BackupGroupRecord = BackupGroupRecord(
        groupId = value.requiredString("groupId"),
        name = value.requiredString("name"),
        groupOrder = value.requiredLong("groupOrder"),
        createdAtEpochMillis = value.requiredLong("createdAtEpochMillis"),
        members = value.requiredArray("members").map { element ->
            val member = element.jsonObject
            BackupGroupMember(
                identity = decodeIdentity(member.requiredObject("identity")),
                groupOrder = member.requiredLong("groupOrder"),
            )
        },
    )

    private fun decodeIdentity(value: JsonObject): BackupChannelIdentity = BackupChannelIdentity(
        providerKey = value.requiredString("providerKey"),
        sourceKind = value.requiredString("sourceKind"),
        sourceName = value.requiredString("sourceName"),
        sourceId = value.optionalString("sourceId"),
    )

    private fun validate(backup: PersonalizationBackupV1) {
        require(backup.createdAtEpochMillis >= 0L)
        val channelKeys = backup.channels.map { record -> record.identity.stableKey() }
        require(channelKeys.size == channelKeys.distinct().size)
        backup.channels.forEach(::validateChannelRecord)

        val groupIds = backup.groups.map(BackupGroupRecord::groupId)
        require(groupIds.size == groupIds.distinct().size)
        backup.groups.forEach { group ->
            require(group.groupId.isNotBlank())
            require(group.name.isNotBlank())
            require(group.groupOrder >= 0L)
            require(group.createdAtEpochMillis >= 0L)
            val memberKeys = group.members.map { member -> member.identity.stableKey() }
            require(memberKeys.size == memberKeys.distinct().size)
            group.members.forEach { member ->
                validateIdentity(member.identity)
                require(member.groupOrder >= 0L)
            }
        }
    }

    private fun validateChannelRecord(record: BackupChannelRecord) {
        validateIdentity(record.identity)
        record.localDisplayName?.let { require(it.isNotBlank()) }
        record.manualOrder?.let { require(it >= 0L) }
        record.hiddenAtEpochMillis?.let { require(it >= 0L) }
        record.favoriteOrder?.let { require(it >= 0L) }
        record.favoriteAddedAtEpochMillis?.let { require(it >= 0L) }
        require((record.favoriteOrder == null) == (record.favoriteAddedAtEpochMillis == null))
        require(
            record.localDisplayName != null ||
                record.manualOrder != null ||
                record.hiddenAtEpochMillis != null ||
                record.favoriteOrder != null ||
                record.logoOverrideOmitted,
        )
    }

    private fun validateIdentity(identity: BackupChannelIdentity) {
        require(identity.providerKey.isNotBlank())
        require(identity.sourceKind.isNotBlank())
        require(identity.sourceName.isNotBlank())
        identity.sourceId?.let { require(it.isNotBlank()) }
    }
}

data class RestoreChannelCandidate(
    val channelId: String,
    val sourceId: String,
    val providerKey: String,
    val sourceKind: String,
    val sourceName: String,
)

sealed interface BackupChannelMatch {
    data class Matched(val candidate: RestoreChannelCandidate) : BackupChannelMatch
    data object Unmatched : BackupChannelMatch
    data object Ambiguous : BackupChannelMatch
}

object BackupChannelMatcher {
    fun resolve(
        identity: BackupChannelIdentity,
        candidates: List<RestoreChannelCandidate>,
    ): BackupChannelMatch {
        val providerMatches = candidates.filter { candidate ->
            candidate.providerKey == identity.providerKey
        }
        if (providerMatches.isEmpty()) return BackupChannelMatch.Unmatched

        val kindMatches = providerMatches.filter { candidate ->
            candidate.sourceKind == identity.sourceKind
        }
        if (kindMatches.isEmpty()) return BackupChannelMatch.Unmatched

        identity.sourceId?.let { sourceId ->
            val sourceIdMatches = kindMatches.filter { candidate -> candidate.sourceId == sourceId }
            when {
                sourceIdMatches.size == 1 -> return BackupChannelMatch.Matched(sourceIdMatches.single())
                sourceIdMatches.size > 1 -> return BackupChannelMatch.Ambiguous
            }
        }

        val nameMatches = kindMatches.filter { candidate ->
            candidate.sourceName.equals(identity.sourceName, ignoreCase = true)
        }
        return when {
            nameMatches.size == 1 -> BackupChannelMatch.Matched(nameMatches.single())
            nameMatches.size > 1 -> BackupChannelMatch.Ambiguous
            kindMatches.size == 1 -> BackupChannelMatch.Matched(kindMatches.single())
            else -> BackupChannelMatch.Ambiguous
        }
    }
}

internal fun BackupChannelIdentity.stableKey(): String =
    "${sourceId.orEmpty()}\u0000$sourceKind\u0000$sourceName\u0000$providerKey"

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableString(
    key: String,
    value: String?,
) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableLong(
    key: String,
    value: Long?,
) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObject.requiredString(key: String): String =
    getValue(key).jsonPrimitive.content.also { value -> require(value.isNotBlank()) }

private fun JsonObject.requiredLong(key: String): Long = getValue(key).jsonPrimitive.long

private fun JsonObject.requiredArray(key: String): JsonArray = getValue(key).jsonArray

private fun JsonObject.requiredObject(key: String): JsonObject = getValue(key).jsonObject

private fun JsonObject.optionalString(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.content
}

private fun JsonObject.optionalLong(key: String): Long? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.long
}

private fun JsonObject.optionalBoolean(key: String): Boolean? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.boolean
}
