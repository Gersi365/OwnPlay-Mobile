package app.ownplay.player.source.xtream

data class XtreamAccountInfo(
    val status: String?,
    val expiresAtEpochSeconds: Long?,
    val maxConnections: Int?,
    val isTrial: Boolean?,
    val allowedOutputFormats: List<String>,
    val serverInfo: XtreamServerInfo?,
)

data class XtreamServerInfo(
    val protocol: String?,
    val timezone: String?,
)

data class XtreamCategory(
    val id: String,
    val name: String,
    val parentId: String?,
)

data class XtreamLiveStream(
    val streamId: Int,
    val name: String,
    val categoryId: String?,
    val iconUrl: String?,
    val epgChannelId: String?,
    val archiveDurationDays: Int?,
    val directSource: String?,
) {
    override fun toString(): String =
        "XtreamLiveStream(streamId=$streamId, name=$name, categoryId=$categoryId, " +
            "iconUrl=${redacted(iconUrl)}, epgChannelId=$epgChannelId, " +
            "archiveDurationDays=$archiveDurationDays, directSource=${redacted(directSource)})"
}

data class XtreamVodStream(
    val streamId: Int,
    val name: String,
    val categoryId: String?,
    val posterUrl: String?,
    val containerExtension: String?,
    val rating: Double?,
    val addedAtEpochSeconds: Long?,
    val directSource: String?,
) {
    override fun toString(): String =
        "XtreamVodStream(streamId=$streamId, name=$name, categoryId=$categoryId, " +
            "posterUrl=${redacted(posterUrl)}, containerExtension=$containerExtension, " +
            "rating=$rating, addedAtEpochSeconds=$addedAtEpochSeconds, " +
            "directSource=${redacted(directSource)})"
}

data class XtreamVodInfo(
    val streamId: Int?,
    val name: String?,
    val originalName: String?,
    val description: String?,
    val posterUrl: String?,
    val backdropUrls: List<String>,
    val releaseDate: String?,
    val durationSeconds: Long?,
    val durationLabel: String?,
    val genre: String?,
    val country: String?,
    val director: String?,
    val cast: String?,
    val rating: Double?,
    val youtubeTrailer: String?,
    val containerExtension: String?,
    val categoryId: String?,
    val directSource: String?,
) {
    override fun toString(): String =
        "XtreamVodInfo(streamId=$streamId, name=$name, originalName=$originalName, " +
            "description=${redacted(description)}, posterUrl=${redacted(posterUrl)}, " +
            "backdropUrls=<${backdropUrls.size} refs>, releaseDate=$releaseDate, " +
            "durationSeconds=$durationSeconds, durationLabel=$durationLabel, genre=$genre, " +
            "country=$country, director=$director, cast=${redacted(cast)}, rating=$rating, " +
            "youtubeTrailer=${redacted(youtubeTrailer)}, containerExtension=$containerExtension, " +
            "categoryId=$categoryId, directSource=${redacted(directSource)})"
}

private fun redacted(value: String?): String = if (value == null) "null" else "<redacted>"
