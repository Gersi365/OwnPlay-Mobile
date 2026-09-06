package app.ownplay.player.source.m3u

data class M3uPlaylist(
    val entries: List<M3uEntry>,
    val epgUrls: List<String> = emptyList(),
) {
    override fun toString(): String =
        "M3uPlaylist(entries=${entries.size}, epgUrls=<${epgUrls.size} redacted>)"
}

data class M3uEntry(
    val displayName: String,
    val streamUrl: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    override fun toString(): String =
        "M3uEntry(displayName=$displayName, streamUrl=<redacted>, tvgId=$tvgId, " +
            "tvgName=$tvgName, logoUrl=${redacted(logoUrl)}, groupTitle=$groupTitle, " +
            "attributes=<${attributes.size} redacted>)"
}

private fun redacted(value: String?): String = if (value == null) "null" else "<redacted>"
