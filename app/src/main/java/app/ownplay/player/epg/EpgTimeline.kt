package app.ownplay.player.epg

data class EpgTimeline(
    val programs: List<EpgProgram>,
    val past: List<EpgProgram>,
    val current: EpgProgram?,
    val future: List<EpgProgram>,
)

object EpgTimelineProjector {
    fun normalize(programs: List<EpgProgram>): List<EpgProgram> = programs
        .distinctBy { program ->
            listOf(
                program.startEpochSeconds,
                program.endEpochSeconds,
                program.title.trim(),
            )
        }
        .sortedWith(
            compareBy<EpgProgram> { it.startEpochSeconds == null }
                .thenBy { it.startEpochSeconds ?: Long.MAX_VALUE }
                .thenBy { it.endEpochSeconds ?: Long.MAX_VALUE }
                .thenBy { it.title },
        )

    fun project(
        programs: List<EpgProgram>,
        nowEpochSeconds: Long,
    ): EpgTimeline {
        val normalized = normalize(programs)
        val current = selectCurrentEpgProgram(normalized, nowEpochSeconds)
        val past = normalized.filter { program ->
            program !== current && when {
                program.endEpochSeconds != null -> program.endEpochSeconds <= nowEpochSeconds
                program.startEpochSeconds != null -> program.startEpochSeconds < nowEpochSeconds
                else -> false
            }
        }
        val future = normalized.filter { program ->
            program !== current && when {
                program.startEpochSeconds != null -> program.startEpochSeconds >= nowEpochSeconds
                else -> false
            }
        }
        return EpgTimeline(
            programs = normalized,
            past = past,
            current = current,
            future = future,
        )
    }
}

internal fun selectCurrentEpgProgram(
    programs: List<EpgProgram>,
    nowEpochSeconds: Long,
): EpgProgram? = programs
    .asSequence()
    .filter { program ->
        val start = program.startEpochSeconds
        val end = program.endEpochSeconds
        start != null && end != null && nowEpochSeconds >= start && nowEpochSeconds < end
    }
    .maxWithOrNull(
        compareBy<EpgProgram> { it.startEpochSeconds ?: Long.MIN_VALUE }
            .thenBy { it.endEpochSeconds ?: Long.MIN_VALUE }
            .thenBy(EpgProgram::title),
    )
