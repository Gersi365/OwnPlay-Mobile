package app.ownplay.mobile.sources.domain

import java.net.URI

object SourceConnectionSecurityPolicy {
    private const val MAX_URL_LENGTH = 4_096

    fun normalizeXtreamBaseUrl(raw: String): ConnectionValidation {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_URL_LENGTH) {
            return ConnectionValidation.Invalid(ConnectionRejection.INVALID_URL)
        }

        val uri = parse(value) ?: return ConnectionValidation.Invalid(ConnectionRejection.INVALID_URL)
        if (!isSupportedScheme(uri.scheme) || uri.host.isNullOrBlank()) {
            return ConnectionValidation.Invalid(ConnectionRejection.INVALID_URL)
        }
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) {
            return ConnectionValidation.Invalid(ConnectionRejection.EMBEDDED_SECRET_OR_QUERY)
        }

        val normalizedPath = uri.path
            ?.trimEnd('/')
            ?.takeUnless { it.isBlank() || it == "/" }
            ?: ""

        val normalized = URI(
            uri.scheme.lowercase(),
            null,
            uri.host.lowercase(),
            uri.port,
            normalizedPath,
            null,
            null,
        ).toASCIIString()

        return ConnectionValidation.Valid(normalized)
    }

    fun normalizeRemoteMediaUrl(raw: String): ConnectionValidation {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_URL_LENGTH) {
            return ConnectionValidation.Invalid(ConnectionRejection.INVALID_URL)
        }

        val uri = parse(value) ?: return ConnectionValidation.Invalid(ConnectionRejection.INVALID_URL)
        if (!isSupportedScheme(uri.scheme) || uri.host.isNullOrBlank()) {
            return ConnectionValidation.Invalid(ConnectionRejection.INVALID_URL)
        }
        if (uri.fragment != null) {
            return ConnectionValidation.Invalid(ConnectionRejection.FRAGMENT_NOT_ALLOWED)
        }

        return ConnectionValidation.Valid(uri.normalize().toASCIIString())
    }

    private fun parse(value: String): URI? =
        runCatching { URI(value) }.getOrNull()

    private fun isSupportedScheme(value: String?): Boolean =
        value.equals("https", ignoreCase = true) || value.equals("http", ignoreCase = true)
}

sealed interface ConnectionValidation {
    data class Valid(val normalizedUrl: String) : ConnectionValidation
    data class Invalid(val reason: ConnectionRejection) : ConnectionValidation
}

enum class ConnectionRejection {
    INVALID_URL,
    EMBEDDED_SECRET_OR_QUERY,
    FRAGMENT_NOT_ALLOWED,
}
