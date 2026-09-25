package app.ownplay.mobile.sources.data.m3u

data class M3uEntry(
    val name: String,
    val groupTitle: String?,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val streamUrl: String,
    val catchUpMode: String? = null,
    val catchUpDays: Int? = null,
    val catchUpSource: String? = null,
) {
    override fun toString(): String =
        "M3uEntry(name=$name, groupTitle=$groupTitle, tvgId=$tvgId, tvgName=$tvgName, " +
            "logoUrl=<redacted>, streamUrl=<redacted>, catchUpMode=$catchUpMode, " +
            "catchUpDays=$catchUpDays, catchUpSource=${if (catchUpSource == null) "<none>" else "<redacted>"})"
}

data class M3uParseResult(
    val entries: List<M3uEntry>,
    val skippedEntries: Int,
)
