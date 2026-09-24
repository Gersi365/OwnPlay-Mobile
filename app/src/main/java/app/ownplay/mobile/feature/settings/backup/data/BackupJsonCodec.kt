package app.ownplay.mobile.feature.settings.backup.data

import app.ownplay.mobile.downloads.domain.DownloadPreferences
import app.ownplay.mobile.feature.live.domain.LiveOrganizationMode
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferences
import app.ownplay.mobile.feature.settings.backup.domain.*
import app.ownplay.mobile.feature.settings.domain.DisplayPreferences
import app.ownplay.mobile.feature.settings.domain.SourceRefreshSchedule
import app.ownplay.mobile.sources.domain.SourceType
import kotlinx.serialization.json.*
import java.nio.charset.StandardCharsets

internal sealed interface BackupJsonDecodeResult {
    data class Success(val envelope: OwnPlayBackupEnvelope) : BackupJsonDecodeResult
    data class Failure(val issues: List<BackupValidationIssue>) : BackupJsonDecodeResult
}

internal class BackupJsonCodec(
    private val json: Json = Json { ignoreUnknownKeys = false },
) {
    fun encode(envelope: OwnPlayBackupEnvelope): ByteArray {
        val issues = BackupValidator.validate(envelope)
        require(issues.isEmpty()) { "Backup envelope is invalid: ${issues.first().code}" }
        return envelope.toJson().toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): BackupJsonDecodeResult {
        val root = runCatching {
            json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
        }.getOrNull() ?: return failure(BackupValidationCode.INVALID_BACKUP_FILE, "$")
        findForbiddenSecretPath(root)?.let { path ->
            return failure(BackupValidationCode.FORBIDDEN_SECRET_FIELD, path)
        }

        val envelope = try {
            root.toEnvelope()
        } catch (error: BackupJsonFieldException) {
            return failure(BackupValidationCode.INVALID_FIELD, error.path)
        } catch (_: Exception) {
            return failure(BackupValidationCode.INVALID_BACKUP_FILE, "$")
        }

        val issues = BackupValidator.validate(envelope)
        return if (issues.isEmpty()) {
            BackupJsonDecodeResult.Success(envelope)
        } else {
            BackupJsonDecodeResult.Failure(issues)
        }
    }

    private fun failure(
        code: BackupValidationCode,
        path: String,
    ): BackupJsonDecodeResult.Failure =
        BackupJsonDecodeResult.Failure(listOf(BackupValidationIssue(code, path)))
}

private class BackupJsonFieldException(val path: String) : IllegalArgumentException(path)

private val forbiddenSecretKeys = setOf(
    "username",
    "password",
    "credentialreference",
    "credential_reference",
    "credentials",
    "secret",
    "token",
    "playlisturl",
    "playlist_url",
    "epgurl",
    "epg_url",
)

private fun findForbiddenSecretPath(
    element: JsonElement,
    path: String = "$",
): String? = when (element) {
    is JsonObject -> element.entries.firstNotNullOfOrNull { (key, value) ->
        val childPath = "$path.$key"
        if (key.lowercase() in forbiddenSecretKeys) childPath
        else findForbiddenSecretPath(value, childPath)
    }
    is JsonArray -> element.withIndex().firstNotNullOfOrNull { (index, value) ->
        findForbiddenSecretPath(value, "$path[$index]")
    }
    else -> null
}

private fun OwnPlayBackupEnvelope.toJson(): JsonObject = buildJsonObject {
    put("format", format)
    put("version", version)
    put("createdAt", createdAt)
    put("payload", payload.toJson())
}

private fun OwnPlayBackupPayload.toJson(): JsonObject = buildJsonObject {
    putJsonArray("sources") { sources.forEach { add(it.toJson()) } }
    putNullableString("activeSourceId", activeSourceId)
    put("globalSettings", globalSettings.toJson())
    putJsonArray("sourceSettings") { sourceSettings.forEach { add(it.toJson()) } }
    putJsonArray("categoryPersonalization") {
        categoryPersonalization.forEach { add(it.toJson()) }
    }
    putJsonArray("channelPersonalization") {
        channelPersonalization.forEach { add(it.toJson()) }
    }
    putJsonArray("mediaFavorites") { mediaFavorites.forEach { add(it.toJson()) } }
    putJsonArray("liveCategoryPersonalization") {
        liveCategoryPersonalization.forEach { add(it.toJson()) }
    }
    putJsonArray("livePlacementOverrides") {
        livePlacementOverrides.forEach { add(it.toJson()) }
    }
    putJsonArray("customGroups") { customGroups.forEach { add(it.toJson()) } }
    putJsonArray("customGroupMemberships") {
        customGroupMemberships.forEach { add(it.toJson()) }
    }
}
private fun BackupSourceDefinition.toJson(): JsonObject = buildJsonObject {
    put("sourceId", sourceId)
    put("type", type.name)
    put("displayName", displayName)
    put("baseLocator", baseLocator)
    put("enabled", enabled)
}

private fun BackupGlobalSettings.toJson(): JsonObject = buildJsonObject {
    putJsonObject("display") {
        put("compactMediaRows", display.compactMediaRows)
        put("showChannelLogos", display.showChannelLogos)
        put("preferTvgName", display.preferTvgName)
        put("hideChannelPrefix", display.hideChannelPrefix)
        put("hideCategoryPrefix", display.hideCategoryPrefix)
    }
    putJsonObject("playback") {
        put("automaticPictureInPicture", playback.automaticPictureInPicture)
        put("playerVolume", playback.playerVolume)
    }
    putJsonObject("downloads") {
        put("unmeteredNetworkOnly", downloads.unmeteredNetworkOnly)
        put("destinationRelativePath", downloads.destinationRelativePath)
        put("notificationsEnabled", downloads.notificationsEnabled)
    }
}

private fun BackupSourceSettings.toJson(): JsonObject = buildJsonObject {
    put("sourceId", sourceId)
    put("refreshSchedule", refreshSchedule.name)
    put("refreshWifiOnly", refreshWifiOnly)
    put("liveOrganizationMode", liveOrganizationMode.name)
}

private fun BackupCategoryPersonalization.toJson(): JsonObject = buildJsonObject {
    put("sourceId", sourceId)
    put("kind", kind.name)
    put("categoryKey", categoryKey)
    put("hidden", hidden)
    putNullableInt("manualOrder", manualOrder)
}

private fun BackupChannelPersonalization.toJson(): JsonObject = buildJsonObject {
    put("sourceId", sourceId)
    put("channelId", channelId)
    put("favorite", favorite)
    put("hidden", hidden)
    putNullableString("localName", localName)
    putNullableString("localLogo", localLogo)
    putNullableInt("manualOrder", manualOrder)
}

private fun BackupMediaFavorite.toJson(): JsonObject = buildJsonObject {
    put("sourceId", sourceId)
    put("mediaKind", mediaKind.name)
    put("contentId", contentId)
    put("addedAt", addedAt)
}

private fun BackupLiveCategoryPersonalization.toJson(): JsonObject = buildJsonObject {
    put("sourceId", sourceId)
    put("organizationMode", organizationMode.name)
    put("categoryId", categoryId)
    put("hidden", hidden)
    putNullableInt("manualOrder", manualOrder)
}

private fun BackupLivePlacementOverride.toJson(): JsonObject = buildJsonObject {
    put("sourceId", sourceId)
    put("categoryId", categoryId)
    put("channelId", channelId)
    put("hidden", hidden)
    putNullableInt("manualOrder", manualOrder)
}

private fun BackupCustomGroup.toJson(): JsonObject = buildJsonObject {
    put("sourceId", sourceId)
    put("groupId", groupId)
    put("name", name)
    put("manualOrder", manualOrder)
}

private fun BackupCustomGroupMembership.toJson(): JsonObject = buildJsonObject {
    put("groupId", groupId)
    put("channelId", channelId)
    put("manualOrder", manualOrder)
}

private fun JsonObject.toEnvelope(): OwnPlayBackupEnvelope = OwnPlayBackupEnvelope(
    format = requiredString("format", "$"),
    version = requiredInt("version", "$"),
    createdAt = requiredString("createdAt", "$"),
    payload = requiredObject("payload", "$").toPayload("$.payload"),
)

private fun JsonObject.toPayload(path: String): OwnPlayBackupPayload = OwnPlayBackupPayload(
    sources = objectList("sources", path) { obj, itemPath -> obj.toSource(itemPath) },
    activeSourceId = nullableString("activeSourceId", path),
    globalSettings = optionalObject("globalSettings", path)
        ?.toGlobalSettings("$path.globalSettings")
        ?: BackupGlobalSettings(),
    sourceSettings = objectList("sourceSettings", path) { obj, itemPath ->
        obj.toSourceSettings(itemPath)
    },
    categoryPersonalization = objectList("categoryPersonalization", path) { obj, itemPath ->
        obj.toCategoryPersonalization(itemPath)
    },
    channelPersonalization = objectList("channelPersonalization", path) { obj, itemPath ->
        obj.toChannelPersonalization(itemPath)
    },
    mediaFavorites = objectList("mediaFavorites", path) { obj, itemPath ->
        obj.toMediaFavorite(itemPath)
    },
    liveCategoryPersonalization = objectList("liveCategoryPersonalization", path) { obj, itemPath ->
        obj.toLiveCategoryPersonalization(itemPath)
    },
    livePlacementOverrides = objectList("livePlacementOverrides", path) { obj, itemPath ->
        obj.toLivePlacementOverride(itemPath)
    },
    customGroups = objectList("customGroups", path) { obj, itemPath ->
        obj.toCustomGroup(itemPath)
    },
    customGroupMemberships = objectList("customGroupMemberships", path) { obj, itemPath ->
        obj.toCustomGroupMembership(itemPath)
    },
)

private fun JsonObject.toSource(path: String): BackupSourceDefinition = BackupSourceDefinition(
    sourceId = requiredString("sourceId", path),
    type = requiredEnum("type", path),
    displayName = requiredString("displayName", path),
    baseLocator = requiredString("baseLocator", path),
    enabled = requiredBoolean("enabled", path),
)

private fun JsonObject.toGlobalSettings(path: String): BackupGlobalSettings {
    val displayDefaults = DisplayPreferences()
    val playbackDefaults = PlaybackPreferences()
    val downloadDefaults = DownloadPreferences()
    val display = optionalObject("display", path)
    val playback = optionalObject("playback", path)
    val downloads = optionalObject("downloads", path)
    return BackupGlobalSettings(
        display = DisplayPreferences(
            compactMediaRows = display?.optionalBoolean(
                "compactMediaRows", "$path.display", displayDefaults.compactMediaRows,
            ) ?: displayDefaults.compactMediaRows,
            showChannelLogos = display?.optionalBoolean(
                "showChannelLogos", "$path.display", displayDefaults.showChannelLogos,
            ) ?: displayDefaults.showChannelLogos,
            preferTvgName = display?.optionalBoolean(
                "preferTvgName", "$path.display", displayDefaults.preferTvgName,
            ) ?: displayDefaults.preferTvgName,
            hideChannelPrefix = display?.optionalBoolean(
                "hideChannelPrefix", "$path.display", displayDefaults.hideChannelPrefix,
            ) ?: displayDefaults.hideChannelPrefix,
            hideCategoryPrefix = display?.optionalBoolean(
                "hideCategoryPrefix", "$path.display", displayDefaults.hideCategoryPrefix,
            ) ?: displayDefaults.hideCategoryPrefix,
        ),
        playback = PlaybackPreferences(
            automaticPictureInPicture = playback?.optionalBoolean(
                "automaticPictureInPicture",
                "$path.playback",
                playbackDefaults.automaticPictureInPicture,
            ) ?: playbackDefaults.automaticPictureInPicture,
            playerVolume = playback?.optionalFloat(
                "playerVolume", "$path.playback", playbackDefaults.playerVolume,
            ) ?: playbackDefaults.playerVolume,
        ),
        downloads = DownloadPreferences(
            unmeteredNetworkOnly = downloads?.optionalBoolean(
                "unmeteredNetworkOnly",
                "$path.downloads",
                downloadDefaults.unmeteredNetworkOnly,
            ) ?: downloadDefaults.unmeteredNetworkOnly,
            destinationRelativePath = downloads?.optionalString(
                "destinationRelativePath",
                "$path.downloads",
                downloadDefaults.destinationRelativePath,
            ) ?: downloadDefaults.destinationRelativePath,
            notificationsEnabled = downloads?.optionalBoolean(
                "notificationsEnabled",
                "$path.downloads",
                downloadDefaults.notificationsEnabled,
            ) ?: downloadDefaults.notificationsEnabled,
        ),
    )
}

private fun JsonObject.toSourceSettings(path: String): BackupSourceSettings = BackupSourceSettings(
    sourceId = requiredString("sourceId", path),
    refreshSchedule = requiredEnum("refreshSchedule", path),
    refreshWifiOnly = requiredBoolean("refreshWifiOnly", path),
    liveOrganizationMode = requiredEnum("liveOrganizationMode", path),
)

private fun JsonObject.toCategoryPersonalization(path: String) = BackupCategoryPersonalization(
    sourceId = requiredString("sourceId", path),
    kind = requiredEnum("kind", path),
    categoryKey = requiredString("categoryKey", path),
    hidden = requiredBoolean("hidden", path),
    manualOrder = nullableInt("manualOrder", path),
)

private fun JsonObject.toChannelPersonalization(path: String) = BackupChannelPersonalization(
    sourceId = requiredString("sourceId", path),
    channelId = requiredString("channelId", path),
    favorite = requiredBoolean("favorite", path),
    hidden = requiredBoolean("hidden", path),
    localName = nullableString("localName", path),
    localLogo = nullableString("localLogo", path),
    manualOrder = nullableInt("manualOrder", path),
)

private fun JsonObject.toMediaFavorite(path: String) = BackupMediaFavorite(
    sourceId = requiredString("sourceId", path),
    mediaKind = requiredEnum("mediaKind", path),
    contentId = requiredString("contentId", path),
    addedAt = requiredLong("addedAt", path),
)

private fun JsonObject.toLiveCategoryPersonalization(path: String) =
    BackupLiveCategoryPersonalization(
        sourceId = requiredString("sourceId", path),
        organizationMode = requiredEnum("organizationMode", path),
        categoryId = requiredString("categoryId", path),
        hidden = requiredBoolean("hidden", path),
        manualOrder = nullableInt("manualOrder", path),
    )

private fun JsonObject.toLivePlacementOverride(path: String) = BackupLivePlacementOverride(
    sourceId = requiredString("sourceId", path),
    categoryId = requiredString("categoryId", path),
    channelId = requiredString("channelId", path),
    hidden = requiredBoolean("hidden", path),
    manualOrder = nullableInt("manualOrder", path),
)

private fun JsonObject.toCustomGroup(path: String) = BackupCustomGroup(
    sourceId = requiredString("sourceId", path),
    groupId = requiredString("groupId", path),
    name = requiredString("name", path),
    manualOrder = requiredInt("manualOrder", path),
)

private fun JsonObject.toCustomGroupMembership(path: String) = BackupCustomGroupMembership(
    groupId = requiredString("groupId", path),
    channelId = requiredString("channelId", path),
    manualOrder = requiredInt("manualOrder", path),
)

private fun JsonObject.requiredObject(name: String, path: String): JsonObject =
    this[name] as? JsonObject ?: throw BackupJsonFieldException("$path.$name")

private fun JsonObject.optionalObject(name: String, path: String): JsonObject? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    return value as? JsonObject ?: throw BackupJsonFieldException("$path.$name")
}

private fun JsonObject.requiredString(name: String, path: String): String {
    val value = this[name] as? JsonPrimitive ?: throw BackupJsonFieldException("$path.$name")
    return value.contentOrNull ?: throw BackupJsonFieldException("$path.$name")
}

private fun JsonObject.optionalString(
    name: String,
    path: String,
    default: String,
): String {
    val value = this[name] ?: return default
    if (value is JsonNull) return default
    val primitive = value as? JsonPrimitive ?: throw BackupJsonFieldException("$path.$name")
    return primitive.contentOrNull ?: throw BackupJsonFieldException("$path.$name")
}
private fun JsonObject.nullableString(name: String, path: String): String? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: throw BackupJsonFieldException("$path.$name")
    return primitive.contentOrNull ?: throw BackupJsonFieldException("$path.$name")
}

private fun JsonObject.requiredBoolean(name: String, path: String): Boolean =
    (this[name] as? JsonPrimitive)?.booleanOrNull
        ?: throw BackupJsonFieldException("$path.$name")

private fun JsonObject.optionalBoolean(
    name: String,
    path: String,
    default: Boolean,
): Boolean {
    val value = this[name] ?: return default
    return (value as? JsonPrimitive)?.booleanOrNull
        ?: throw BackupJsonFieldException("$path.$name")
}

private fun JsonObject.requiredInt(name: String, path: String): Int =
    (this[name] as? JsonPrimitive)?.intOrNull
        ?: throw BackupJsonFieldException("$path.$name")

private fun JsonObject.nullableInt(name: String, path: String): Int? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.intOrNull
        ?: throw BackupJsonFieldException("$path.$name")
}

private fun JsonObject.requiredLong(name: String, path: String): Long =
    (this[name] as? JsonPrimitive)?.longOrNull
        ?: throw BackupJsonFieldException("$path.$name")

private fun JsonObject.optionalFloat(
    name: String,
    path: String,
    default: Float,
): Float {
    val value = this[name] ?: return default
    return (value as? JsonPrimitive)?.floatOrNull
        ?: throw BackupJsonFieldException("$path.$name")
}

private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(
    name: String,
    path: String,
): T {
    val raw = requiredString(name, path)
    return enumValues<T>().firstOrNull { it.name == raw }
        ?: throw BackupJsonFieldException("$path.$name")
}

private fun <T> JsonObject.objectList(
    name: String,
    path: String,
    parser: (JsonObject, String) -> T,
): List<T> {
    val value = this[name] ?: return emptyList()
    val array = value as? JsonArray ?: throw BackupJsonFieldException("$path.$name")
    return array.mapIndexed { index, element ->
        val itemPath = "$path.$name[$index]"
        val obj = element as? JsonObject ?: throw BackupJsonFieldException(itemPath)
        parser(obj, itemPath)
    }
}

private fun JsonObjectBuilder.putNullableString(name: String, value: String?) {
    if (value == null) put(name, JsonNull) else put(name, value)
}

private fun JsonObjectBuilder.putNullableInt(name: String, value: Int?) {
    if (value == null) put(name, JsonNull) else put(name, value)
}
