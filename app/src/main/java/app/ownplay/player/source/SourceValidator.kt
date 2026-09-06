package app.ownplay.player.source

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

sealed interface UrlValidationResult {
    data class Valid(
        val normalizedUrl: String,
        val usesCleartext: Boolean,
    ) : UrlValidationResult {
        override fun toString(): String =
            "UrlValidationResult.Valid(normalizedUrl=<redacted>, usesCleartext=$usesCleartext)"
    }

    data class Invalid(val error: SourceError) : UrlValidationResult
}

object SourceValidator {
    fun validateXtreamServer(rawUrl: String): UrlValidationResult =
        validateRemoteUrl(rawUrl = rawUrl, allowQuery = false, ensureTrailingSlash = true)

    fun validateRemotePlaylistUrl(rawUrl: String): UrlValidationResult =
        validateRemoteUrl(rawUrl = rawUrl, allowQuery = true, ensureTrailingSlash = false)

    fun validateLocalDocumentUri(rawUri: String): SourceError? {
        val value = rawUri.trim()
        if (value.isEmpty()) return SourceError.EmptyValue

        val uri = parseUri(value) ?: return SourceError.InvalidUrl
        return if (
            uri.scheme.equals("content", ignoreCase = true) &&
            !uri.rawAuthority.isNullOrBlank()
        ) {
            null
        } else {
            SourceError.UnsupportedLocalUri
        }
    }

    private fun validateRemoteUrl(
        rawUrl: String,
        allowQuery: Boolean,
        ensureTrailingSlash: Boolean,
    ): UrlValidationResult {
        val value = rawUrl.trim()
        if (value.isEmpty()) return UrlValidationResult.Invalid(SourceError.EmptyValue)

        val uri = parseUri(value) ?: return UrlValidationResult.Invalid(SourceError.InvalidUrl)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: return UrlValidationResult.Invalid(SourceError.UnsupportedScheme)

        if (scheme != "http" && scheme != "https") {
            return UrlValidationResult.Invalid(SourceError.UnsupportedScheme)
        }
        if (uri.host.isNullOrBlank()) {
            return UrlValidationResult.Invalid(SourceError.MissingHost)
        }
        if (uri.port == 0 || uri.port > 65_535) {
            return UrlValidationResult.Invalid(SourceError.InvalidUrl)
        }
        if (uri.userInfo != null) {
            return UrlValidationResult.Invalid(SourceError.EmbeddedCredentialsNotAllowed)
        }
        if (uri.fragment != null || (!allowQuery && uri.query != null)) {
            return UrlValidationResult.Invalid(SourceError.UnexpectedUrlComponent)
        }

        val normalized = if (ensureTrailingSlash) {
            val path = when {
                uri.path.isNullOrEmpty() -> "/"
                uri.path.endsWith('/') -> uri.path
                else -> "${uri.path}/"
            }
            URI(
                scheme,
                null,
                uri.host,
                uri.port,
                path,
                null,
                null,
            ).toString()
        } else {
            URI(
                scheme,
                null,
                uri.host,
                uri.port,
                uri.path.ifEmpty { "/" },
                uri.query,
                null,
            ).toString()
        }

        return UrlValidationResult.Valid(
            normalizedUrl = normalized,
            usesCleartext = scheme == "http",
        )
    }

    private fun parseUri(value: String): URI? = try {
        URI(value)
    } catch (_: URISyntaxException) {
        null
    }
}
