package app.ownplay.player.source.xtream

data class XtreamSourceLocator(
    val serverUrl: String,
    val allowCleartext: Boolean,
) {
    init {
        require(serverUrl.isNotBlank()) { "Xtream server URL must not be blank" }
    }

    override fun toString(): String =
        "XtreamSourceLocator(serverUrl=<redacted>, allowCleartext=$allowCleartext)"
}

object XtreamSourceLocatorCodec {
    private const val PREFIX = "ownplay-xtream-source-v1"
    private const val SECURE_MODE = "secure"
    private const val CLEARTEXT_ALLOWED_MODE = "cleartext-allowed"

    fun encode(locator: XtreamSourceLocator): String {
        val mode = if (locator.allowCleartext) {
            CLEARTEXT_ALLOWED_MODE
        } else {
            SECURE_MODE
        }
        return "$PREFIX|$mode|${locator.serverUrl}"
    }

    fun parse(value: String): XtreamSourceLocator? {
        if (value.isBlank()) return null

        // Backward compatibility: older sources stored only the raw server URL.
        if (!value.startsWith("$PREFIX|")) {
            return XtreamSourceLocator(
                serverUrl = value,
                allowCleartext = false,
            )
        }

        val parts = value.split('|', limit = 3)
        if (parts.size != 3 || parts[0] != PREFIX || parts[2].isBlank()) {
            return null
        }

        val allowCleartext = when (parts[1]) {
            SECURE_MODE -> false
            CLEARTEXT_ALLOWED_MODE -> true
            else -> return null
        }
        return XtreamSourceLocator(
            serverUrl = parts[2],
            allowCleartext = allowCleartext,
        )
    }
}
