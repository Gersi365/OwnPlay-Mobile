package app.ownplay.mobile.feature.live.domain

object LiveGuidePolicy {
    fun normalizeSchedule(programs: List<LiveProgram>): List<LiveProgram> =
        programs
            .map { program -> program.copy(title = program.title.trim()) }
            .filter { it.title.isNotBlank() }
            .sortedWith(
                compareBy<LiveProgram> { it.startEpochSeconds ?: Long.MAX_VALUE }
                    .thenBy { it.endEpochSeconds ?: Long.MAX_VALUE },
            )

    fun nowNext(programs: List<LiveProgram>, nowEpochSeconds: Long): LiveNowNext {
        val usable = programs.filter { it.title.isNotBlank() }
        if (usable.isEmpty()) return LiveNowNext()

        val current = usable.firstOrNull { program ->
            val start = program.startEpochSeconds
            val end = program.endEpochSeconds
            start != null && end != null && start <= nowEpochSeconds && nowEpochSeconds < end
        }
        val next = if (current != null) {
            val currentIndex = usable.indexOf(current)
            usable.drop(currentIndex + 1).firstOrNull { candidate ->
                candidate.startEpochSeconds == null ||
                    candidate.startEpochSeconds >= (current.endEpochSeconds ?: nowEpochSeconds)
            }
        } else {
            usable.firstOrNull { candidate ->
                candidate.startEpochSeconds?.let { it > nowEpochSeconds } == true
            }
        }
        if (current != null || next != null) return LiveNowNext(now = current, next = next)

        val timingAbsent = usable.all { program ->
            program.startEpochSeconds == null && program.endEpochSeconds == null
        }
        if (!timingAbsent) return LiveNowNext()
        return LiveNowNext(now = usable.getOrNull(0), next = usable.getOrNull(1))
    }

    fun nextBoundaryEpochSeconds(guide: LiveNowNext, nowEpochSeconds: Long): Long? =
        sequenceOf(guide.now?.endEpochSeconds, guide.next?.startEpochSeconds)
            .filterNotNull()
            .filter { it > nowEpochSeconds }
            .minOrNull()

    fun progressFraction(program: LiveProgram?, nowEpochSeconds: Long): Float? {
        val start = program?.startEpochSeconds ?: return null
        val end = program.endEpochSeconds ?: return null
        if (end <= start) return null
        return ((nowEpochSeconds - start).toDouble() / (end - start).toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()
    }
}
