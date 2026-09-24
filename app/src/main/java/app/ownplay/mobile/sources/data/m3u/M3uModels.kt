package app.ownplay.mobile.sources.data.m3u

data class M3uEntry(
    val name: String,
    val groupTitle: String?,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val streamUrl: String,
) {
    override fun toString(): String =
        "M3uEntry(name=$name, groupTitle=$groupTitle, tvgId=$tvgId, tvgName=$tvgName, " +
            "logoUrl=<redacted>, streamUrl=<redacted>)"
}

data class M3uParseResult(
    val entries: List<M3uEntry>,
    val skippedEntries: Int,
)
