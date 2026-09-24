package app.ownplay.mobile.feature.live.domain

import app.ownplay.mobile.sources.domain.SourceId

data class LiveCatchUpProgram(
    val programId: String,
    val title: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val resumePositionMs: Long? = null,
    val progressDurationMs: Long? = null,
    val completed: Boolean = false,
) {
    val durationSeconds: Long
        get() = (endEpochSeconds - startEpochSeconds).coerceAtLeast(0L)
}

data class LiveCatchUpCatalog(
    val supported: Boolean,
    val programs: List<LiveCatchUpProgram> = emptyList(),
)

interface LiveCatchUpRepository {
    suspend fun loadCatalog(sourceId: SourceId, channelId: String): LiveCatchUpCatalog
}

object LiveCatchUpPolicy {
    fun eligiblePrograms(
        channelId: String,
        programs: List<LiveProgram>,
        nowEpochSeconds: Long,
        archiveDurationDays: Int?,
    ): List<LiveCatchUpProgram> {
        if (channelId.isBlank()) return emptyList()
        val cutoff = archiveDurationDays
            ?.takeIf { it > 0 }
            ?.let { days ->
                val seconds = days.toLong().coerceAtMost(Long.MAX_VALUE / SECONDS_PER_DAY) *
                    SECONDS_PER_DAY
                (nowEpochSeconds - seconds).coerceAtLeast(0L)
            }

        return programs
            .asSequence()
            .mapNotNull { program ->
                val start = program.startEpochSeconds ?: return@mapNotNull null
                val end = program.endEpochSeconds ?: return@mapNotNull null
                if (program.title.isBlank() || start <= 0L || end <= start || end > nowEpochSeconds) {
                    return@mapNotNull null
                }
                if (cutoff != null && end < cutoff) return@mapNotNull null
                LiveCatchUpProgram(
                    programId = programId(channelId, start, end),
                    title = program.title.trim(),
                    startEpochSeconds = start,
                    endEpochSeconds = end,
                )
            }
            .distinctBy(LiveCatchUpProgram::programId)
            .sortedWith(
                compareByDescending(LiveCatchUpProgram::startEpochSeconds)
                    .thenByDescending(LiveCatchUpProgram::endEpochSeconds)
                    .thenBy(LiveCatchUpProgram::programId),
            )
            .toList()
    }

    fun withProgress(
        program: LiveCatchUpProgram,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
    ): LiveCatchUpProgram {
        if (positionMs < 0L || durationMs <= 0L) return program
        return program.copy(
            resumePositionMs = positionMs.coerceAtMost(durationMs),
            progressDurationMs = durationMs,
            completed = completed,
        )
    }

    fun progressFraction(program: LiveCatchUpProgram): Float? {
        if (program.completed) return null
        val duration = program.progressDurationMs?.takeIf { it > 0L } ?: return null
        val position = program.resumePositionMs?.takeIf { it > 0L } ?: return null
        return (position.toDouble() / duration.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()
    }

    fun resumablePositionMs(program: LiveCatchUpProgram): Long? {
        if (program.completed) return null
        val duration = program.progressDurationMs?.takeIf { it > 0L } ?: return null
        return program.resumePositionMs?.takeIf { it in 1 until duration }
    }

    fun programId(channelId: String, startEpochSeconds: Long, endEpochSeconds: Long): String =
        "$channelId:$startEpochSeconds:$endEpochSeconds"

    private const val SECONDS_PER_DAY = 86_400L
}
