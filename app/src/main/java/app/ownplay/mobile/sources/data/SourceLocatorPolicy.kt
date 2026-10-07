package app.ownplay.mobile.sources.data

import java.net.URI

object SourceLocatorPolicy {
    fun connectionLabel(rawUrl: String): String {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull()
            ?: return "Configured source"
        val host = uri.host?.lowercase()?.takeIf(String::isNotBlank)
            ?: return "Configured source"
        val scheme = uri.scheme?.lowercase()?.takeIf(String::isNotBlank)
            ?: return host
        val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
        val path = uri.path
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() && it != "/" }
            .orEmpty()
        return "$scheme://$host$port$path"
    }

    fun redact(rawUrl: String): String = connectionLabel(rawUrl)
}
