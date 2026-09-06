package app.ownplay.player.source.xtream

data class XtreamEpgProgram(
    val id: String?,
    val title: String,
    val description: String?,
    val startEpochSeconds: Long?,
    val endEpochSeconds: Long?,
    val startLabel: String?,
    val endLabel: String?,
)

data class XtreamEpgGuide(
    val programs: List<XtreamEpgProgram>,
)
