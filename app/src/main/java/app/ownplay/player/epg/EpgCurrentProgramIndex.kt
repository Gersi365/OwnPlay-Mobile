package app.ownplay.player.epg

internal object EpgCurrentProgramIndex {
    fun currentByChannel(
        channelIdsByEpgChannelId: Map<String, List<String>>,
        programsByEpgChannelId: Map<String, List<EpgProgram>>,
        nowEpochSeconds: Long,
    ): Map<String, EpgProgram> = buildMap {
        channelIdsByEpgChannelId.forEach { (epgChannelId, channelIds) ->
            val current = currentProgram(
                programs = programsByEpgChannelId[epgChannelId].orEmpty(),
                nowEpochSeconds = nowEpochSeconds,
            ) ?: return@forEach
            channelIds.forEach { channelId -> put(channelId, current) }
        }
    }

    fun currentProgram(
        programs: List<EpgProgram>,
        nowEpochSeconds: Long,
    ): EpgProgram? = selectCurrentEpgProgram(programs, nowEpochSeconds)
}
