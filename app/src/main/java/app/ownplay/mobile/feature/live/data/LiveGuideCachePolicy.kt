package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.feature.live.domain.LiveGuidePolicy
import app.ownplay.mobile.feature.live.domain.LiveProgram

internal data class LiveGuideCacheCandidate(
    val fetchedAtMs: Long,
    val programs: List<LiveProgram>,
)

internal object LiveGuideCachePolicy {
    fun selectForNowNext(
        candidates: List<LiveGuideCacheCandidate>,
        nowEpochSeconds: Long,
    ): LiveGuideCacheCandidate? = candidates.maxWithOrNull(
        compareBy<LiveGuideCacheCandidate> { candidate ->
            val guide = LiveGuidePolicy.nowNext(candidate.programs, nowEpochSeconds)
            when {
                guide.now != null -> 3
                guide.next != null -> 2
                candidate.programs.isNotEmpty() -> 1
                else -> 0
            }
        }.thenBy { it.fetchedAtMs },
    )

    fun shouldPreserveLastGood(
        existingPrograms: List<LiveProgram>,
        incomingPrograms: List<LiveProgram>,
    ): Boolean = existingPrograms.isNotEmpty() && incomingPrograms.isEmpty()
}
