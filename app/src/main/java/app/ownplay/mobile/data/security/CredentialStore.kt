package app.ownplay.mobile.data.security

import app.ownplay.mobile.sources.domain.SourceId

sealed interface SourceSecret {
    class Xtream(
        val username: String,
        val password: String,
    ) : SourceSecret {
        override fun toString(): String = "Xtream(username=<redacted>, password=<redacted>)"
    }

    class M3uRemote(
        val playlistUrl: String,
        val epgUrl: String?,
    ) : SourceSecret {
        override fun toString(): String = "M3uRemote(playlistUrl=<redacted>, epgUrl=<redacted>)"
    }
}

interface CredentialStore {
    suspend fun put(sourceId: SourceId, secret: SourceSecret)

    suspend fun get(sourceId: SourceId): SourceSecret?

    suspend fun delete(sourceId: SourceId)
}

class CredentialStoreException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
