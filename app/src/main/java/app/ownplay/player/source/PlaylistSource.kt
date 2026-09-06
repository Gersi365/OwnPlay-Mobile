package app.ownplay.player.source

@JvmInline
value class CredentialRef(val value: String) {
    init {
        require(value.isNotBlank()) { "Credential reference must not be blank" }
    }

    override fun toString(): String = "CredentialRef(<opaque>)"
}

sealed interface PlaylistSource {
    val name: String

    data class Xtream(
        override val name: String,
        val serverUrl: String,
        val credentialRef: CredentialRef,
    ) : PlaylistSource {
        override fun toString(): String =
            "PlaylistSource.Xtream(name=$name, serverUrl=<redacted>, credentialRef=<opaque>)"
    }

    data class RemoteM3u(
        override val name: String,
        val playlistUrl: String,
        val epgUrl: String? = null,
    ) : PlaylistSource {
        override fun toString(): String =
            "PlaylistSource.RemoteM3u(name=$name, playlistUrl=<redacted>, epgUrl=${redacted(epgUrl)})"
    }

    data class LocalM3u(
        override val name: String,
        val documentUri: String,
        val epgUrl: String? = null,
    ) : PlaylistSource {
        override fun toString(): String =
            "PlaylistSource.LocalM3u(name=$name, documentUri=<redacted>, epgUrl=${redacted(epgUrl)})"
    }
}

private fun redacted(value: String?): String = if (value == null) "null" else "<redacted>"
