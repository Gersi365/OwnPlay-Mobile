package app.ownplay.mobile.data.security

object CredentialInputPolicy {
    private const val MAX_DISPLAY_NAME_LENGTH = 120
    private const val MAX_CREDENTIAL_LENGTH = 512

    fun normalizeDisplayName(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_DISPLAY_NAME_LENGTH || value.hasControlCharacters()) {
            return null
        }
        return value
    }

    fun isValidCredential(raw: String): Boolean =
        raw.isNotBlank() &&
            raw.length <= MAX_CREDENTIAL_LENGTH &&
            !raw.hasControlCharacters()

    private fun String.hasControlCharacters(): Boolean =
        any(Char::isISOControl)
}
