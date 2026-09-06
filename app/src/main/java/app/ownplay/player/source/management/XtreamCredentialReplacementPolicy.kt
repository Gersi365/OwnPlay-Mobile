package app.ownplay.player.source.management

internal enum class XtreamCredentialEditMode {
    KEEP_EXISTING,
    REPLACE,
    INCOMPLETE,
}

internal object XtreamCredentialReplacementPolicy {
    fun classify(
        username: String,
        password: String,
    ): XtreamCredentialEditMode {
        val usernameProvided = username.isNotBlank()
        val passwordProvided = password.isNotBlank()
        return when {
            usernameProvided != passwordProvided -> XtreamCredentialEditMode.INCOMPLETE
            usernameProvided -> XtreamCredentialEditMode.REPLACE
            else -> XtreamCredentialEditMode.KEEP_EXISTING
        }
    }
}
