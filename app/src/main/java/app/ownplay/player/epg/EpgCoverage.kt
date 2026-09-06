package app.ownplay.player.epg

internal object EpgCoverage {
    fun matchedChannelCount(
        channelIdsByEpgChannelId: Map<String, List<String>>,
        programsByEpgChannelId: Map<String, List<EpgProgram>>,
    ): Int = programsByEpgChannelId.keys
        .asSequence()
        .flatMap { epgChannelId -> channelIdsByEpgChannelId[epgChannelId].orEmpty().asSequence() }
        .distinct()
        .count()
}
