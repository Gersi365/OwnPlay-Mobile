package app.ownplay.mobile.downloads.domain

import java.util.Base64

data class CatchUpDownloadIdentity(
    val channelId: String,
    val programId: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
) {
    init {
        require(channelId.isNotBlank() && programId.isNotBlank())
        require(startEpochSeconds > 0L && endEpochSeconds > startEpochSeconds)
    }

    fun encode(): String = listOf(
        VERSION,
        channelId.encodePart(),
        programId.encodePart(),
        startEpochSeconds.toString(),
        endEpochSeconds.toString(),
    ).joinToString("|")

    companion object {
        private const val VERSION = "catchup-v1"

        fun decode(value: String?): CatchUpDownloadIdentity? {
            val parts = value?.split('|') ?: return null
            if (parts.size != 5 || parts[0] != VERSION) return null
            val channelId = parts[1].decodePart() ?: return null
            val programId = parts[2].decodePart() ?: return null
            val start = parts[3].toLongOrNull() ?: return null
            val end = parts[4].toLongOrNull() ?: return null
            return runCatching { CatchUpDownloadIdentity(channelId, programId, start, end) }
                .getOrNull()
        }

        private fun String.decodePart(): String? = runCatching {
            String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)
        }.getOrNull()?.takeIf(String::isNotBlank)

        private fun String.encodePart(): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))
    }
}
