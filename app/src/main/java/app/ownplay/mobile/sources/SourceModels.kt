package app.ownplay.mobile.sources

import java.security.MessageDigest

enum class SourceKind {
    XTREAM,
    M3U,
}

enum class CatalogType {
    LIVE,
    MOVIE,
    SERIES,
    EPISODE,
}

enum class CredentialState {
    NOT_REQUIRED,
    AVAILABLE,
    REENTRY_REQUIRED,
}

data class SourceDescriptor(
    val sourceId: String,
    val name: String,
    val kind: SourceKind,
    val locator: String,
    val enabled: Boolean,
    val credentialState: CredentialState,
)

data class SourceDraft(
    val name: String,
    val kind: SourceKind,
    val locator: String,
    val enabled: Boolean = true,
)

data class XtreamCredentials(
    val username: String,
    val password: String,
)

data class ProviderCategory(
    val catalogType: CatalogType,
    val providerKey: String,
    val name: String,
    val providerOrder: Int,
)

data class ProviderItem(
    val catalogType: CatalogType,
    val providerKey: String,
    val categoryKey: String?,
    val name: String,
    val artworkUrl: String?,
    val streamLocator: String?,
    val containerExtension: String?,
    val rating: Double?,
    val providerOrder: Int,
)

data class SourceSnapshot(
    val categories: List<ProviderCategory>,
    val items: List<ProviderItem>,
)

sealed interface RefreshResult {
    data class Success(
        val generation: Long,
        val itemCount: Int,
    ) : RefreshResult

    data class Failure(
        val reason: String,
    ) : RefreshResult
}

fun stableCategoryId(
    sourceId: String,
    catalogType: CatalogType,
    providerKey: String,
): String = stableSourceKey("category", sourceId, catalogType.name, providerKey)

fun stableProviderItemId(
    sourceId: String,
    catalogType: CatalogType,
    providerKey: String,
): String = stableSourceKey("item", sourceId, catalogType.name, providerKey)

fun stableSourceKey(vararg parts: String): String {
    val input = parts.joinToString(separator = "\u001f")
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
