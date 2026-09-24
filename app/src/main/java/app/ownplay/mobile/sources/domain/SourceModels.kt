package app.ownplay.mobile.sources.domain

enum class SourceType {
    XTREAM,
    M3U,
}

@JvmInline
value class SourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "SourceId must not be blank" }
    }
}

data class SourceSummary(
    val sourceId: SourceId,
    val type: SourceType,
    val displayName: String,
    val connectionLabel: String,
    val enabled: Boolean,
    val lastSuccessfulRefreshAtEpochMs: Long?,
    val refreshFailureCategory: SourceRefreshFailureCategory? = null,
)

sealed interface SourceInput {
    val displayName: String

    class Xtream(
        override val displayName: String,
        val serverUrl: String,
        val username: String,
        val password: String,
    ) : SourceInput {
        override fun toString(): String =
            "Xtream(displayName=$displayName, serverUrl=<redacted>, username=<redacted>, password=<redacted>)"
    }

    class M3u(
        override val displayName: String,
        val playlistUrl: String,
        val epgUrl: String?,
    ) : SourceInput {
        override fun toString(): String =
            "M3u(displayName=$displayName, playlistUrl=<redacted>, epgUrl=<redacted>)"
    }
}

sealed interface SourceReconnectInput {
    class Xtream(
        val serverUrl: String,
        val username: String,
        val password: String,
    ) : SourceReconnectInput {
        override fun toString(): String =
            "Xtream(serverUrl=<redacted>, username=<redacted>, password=<redacted>)"
    }

    class M3u(
        val playlistUrl: String,
        val epgUrl: String?,
    ) : SourceReconnectInput {
        override fun toString(): String =
            "M3u(playlistUrl=<redacted>, epgUrl=<redacted>)"
    }
}

sealed interface SourceMutationResult {
    data class Success(val sourceId: SourceId) : SourceMutationResult

    data class Rejected(val reason: SourceMutationRejection) : SourceMutationResult
}

enum class SourceMutationRejection {
    INVALID_NAME,
    INVALID_CONNECTION,
    INVALID_CREDENTIALS,
    DUPLICATE_SOURCE,
    STORAGE_FAILURE,
}
