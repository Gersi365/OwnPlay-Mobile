package app.ownplay.mobile.feature.live.domain

import app.ownplay.mobile.sources.domain.SourceId

data class LiveProgram(
    val title: String,
    val startEpochSeconds: Long?,
    val endEpochSeconds: Long?,
)

data class LiveNowNext(
    val now: LiveProgram? = null,
    val next: LiveProgram? = null,
)

interface LiveGuideRepository {
    suspend fun loadNowNext(sourceId: SourceId, channelId: String): LiveNowNext

    suspend fun loadSchedule(sourceId: SourceId, channelId: String): List<LiveProgram> =
        loadNowNext(sourceId, channelId).let { guide ->
            listOfNotNull(guide.now, guide.next)
        }
}
