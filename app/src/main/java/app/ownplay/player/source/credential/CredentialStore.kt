package app.ownplay.player.source.credential

import app.ownplay.player.source.CredentialRef

data class XtreamCredentials(
    val username: String,
    val password: String,
) {
    override fun toString(): String =
        "XtreamCredentials(username=<redacted>, password=<redacted>)"
}

interface CredentialStore {
    fun put(credentials: XtreamCredentials): CredentialRef
    fun get(ref: CredentialRef): XtreamCredentials?
    fun delete(ref: CredentialRef)
}
