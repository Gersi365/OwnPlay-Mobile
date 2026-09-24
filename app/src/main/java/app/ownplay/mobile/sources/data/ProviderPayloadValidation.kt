package app.ownplay.mobile.sources.data

object ProviderPayloadValidation {
    fun validate(
        body: String,
        kind: ProviderPayloadKind,
    ): ProviderPayloadValidationResult {
        val trimmed = body.trimStart()
        if (trimmed.isBlank()) {
            return ProviderPayloadValidationResult.Invalid(
                ProviderPayloadRejection.EMPTY,
            )
        }

        val prefix = trimmed.take(512).lowercase()
        if (
            prefix.startsWith("<!doctype html") ||
            prefix.startsWith("<html") ||
            prefix.contains("<head") ||
            prefix.contains("<body")
        ) {
            return ProviderPayloadValidationResult.Invalid(
                ProviderPayloadRejection.HTML_RESPONSE,
            )
        }

        return when (kind) {
            ProviderPayloadKind.JSON -> {
                if (trimmed.first() == '[' || trimmed.first() == '{') {
                    ProviderPayloadValidationResult.Valid
                } else {
                    ProviderPayloadValidationResult.Invalid(
                        ProviderPayloadRejection.UNEXPECTED_FORMAT,
                    )
                }
            }

            ProviderPayloadKind.M3U -> {
                if (
                    trimmed.startsWith("#EXTM3U", ignoreCase = true) ||
                    trimmed.contains("#EXTINF:", ignoreCase = true)
                ) {
                    ProviderPayloadValidationResult.Valid
                } else {
                    ProviderPayloadValidationResult.Invalid(
                        ProviderPayloadRejection.UNEXPECTED_FORMAT,
                    )
                }
            }
        }
    }
}

enum class ProviderPayloadKind {
    JSON,
    M3U,
}

sealed interface ProviderPayloadValidationResult {
    data object Valid : ProviderPayloadValidationResult
    data class Invalid(
        val reason: ProviderPayloadRejection,
    ) : ProviderPayloadValidationResult
}

enum class ProviderPayloadRejection {
    EMPTY,
    HTML_RESPONSE,
    UNEXPECTED_FORMAT,
}
