package app.ownplay.mobile.feature.live.domain

object LiveGuidePolicy {
    fun normalizeSchedule(programs: List<LiveProgram>): List<LiveProgram> =
        programs
            .map { program -> program.copy(title = program.title.trim()) }
            .filter { it.title.isNotBlank() }
            .filter { program ->
                val start = program.startEpochSeconds
                val end = program.endEpochSeconds
                start == null || end == null || end > start
            }
            .distinctBy { program ->
                listOf(
                    program.title,
                    program.startEpochSeconds?.toString().orEmpty(),
                    program.endEpochSeconds?.toString().orEmpty(),
                ).joinToString("\u0000")
            }
            .sortedWith(
                compareBy<LiveProgram> { it.startEpochSeconds ?: Long.MAX_VALUE }
                    .thenBy { it.endEpochSeconds ?: Long.MAX_VALUE },
            )

    fun nowNext(programs: List<LiveProgram>, nowEpochSeconds: Long): LiveNowNext {
        val usable = normalizeSchedule(programs)
        if (usable.isEmpty()) return LiveNowNext()

        val current = usable.filter { program ->
            val start = program.startEpochSeconds
            val end = program.endEpochSeconds
            start != null && end != null && start <= nowEpochSeconds && nowEpochSeconds < end
        }.maxWithOrNull(
            compareBy<LiveProgram> { it.startEpochSeconds ?: Long.MIN_VALUE }
                .thenBy { it.endEpochSeconds ?: Long.MAX_VALUE },
        )
        val next = if (current != null) {
            usable.firstOrNull { candidate ->
                candidate.startEpochSeconds != null &&
                    candidate.startEpochSeconds >= (current.endEpochSeconds ?: nowEpochSeconds)
            }
        } else {
            usable.firstOrNull { candidate ->
                candidate.startEpochSeconds?.let { it > nowEpochSeconds } == true
            }
        }
        return LiveNowNext(now = current, next = next)
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
