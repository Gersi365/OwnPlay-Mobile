package app.ownplay.player.persistence

data class PlaylistSourceSummary(
    val sourceId: String,
    val name: String,
    val sourceKind: String,
    val enabled: Boolean,
    val channelCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
