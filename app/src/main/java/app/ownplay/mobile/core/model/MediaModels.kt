package app.ownplay.mobile.core.model

enum class SourceKind {
    XTREAM,
    M3U,
}

data class MediaSource(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val isActive: Boolean,
)

data class ProgramInfo(
    val title: String,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
)

data class LiveChannel(
    val id: String,
    val sourceId: String,
    val name: String,
    val logoUrl: String? = null,
    val category: String? = null,
    val now: ProgramInfo? = null,
    val next: ProgramInfo? = null,
)

data class Movie(
    val id: String,
    val sourceId: String,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
)

data class Series(
    val id: String,
    val sourceId: String,
    val title: String,
    val posterUrl: String? = null,
)

data class ContinueWatchingItem(
    val mediaId: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val positionMillis: Long,
    val durationMillis: Long,
)
