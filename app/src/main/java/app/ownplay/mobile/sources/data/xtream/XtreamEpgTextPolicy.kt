package app.ownplay.mobile.sources.data.xtream

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

object XtreamEpgTextPolicy {
    private val base64Shape = Regex("^[A-Za-z0-9+/=_-]+$")
    private val decimalEntity = Regex("&#([0-9]+);")
    private val hexadecimalEntity = Regex("&#x([0-9A-Fa-f]+);")

    fun decode(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val text = decodeBase64Candidate(trimmed) ?: trimmed
        return decodeHtmlEntities(text).trim()
    }

    private fun decodeBase64Candidate(value: String): String? {
        val compact = value.filterNot(Char::isWhitespace)
        if (compact.length < 8 || compact.length % 4 == 1 || !base64Shape.matches(compact)) return null
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        return sequenceOf(Base64.getDecoder(), Base64.getUrlDecoder())
            .mapNotNull { decoder -> runCatching { decoder.decode(padded) }.getOrNull() }
            .mapNotNull(::decodeUtf8Strict)
            .firstOrNull(::looksHumanReadable)
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun looksHumanReadable(value: String): Boolean {
        val text = value.trim()
        if (text.isEmpty() || text.none(Char::isLetterOrDigit)) return false
        val printable = text.count { character ->
            !Character.isISOControl(character) || character == '\n' || character == '\t'
        }
        return printable.toDouble() / text.length >= 0.90
    }

    private fun decodeHtmlEntities(value: String): String {
        var result = value
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
        result = decimalEntity.replace(result) { match ->
            codePoint(match.groupValues[1].toIntOrNull(), match.value)
        }
        return hexadecimalEntity.replace(result) { match ->
            codePoint(match.groupValues[1].toIntOrNull(16), match.value)
        }
    }

    private fun codePoint(value: Int?, fallback: String): String {
        if (value == null || !Character.isValidCodePoint(value)) return fallback
        return runCatching { String(Character.toChars(value)) }.getOrDefault(fallback)
    }
}
