package app.ownplay.player.ui.tv

internal enum class TvRemoteActionKind(
    val cooldownMillis: Long,
) {
    STANDARD(400L),
    TRANSITION(900L),
}

internal class TvRemoteActionGuard {
    private var globallyBlockedUntilMillis = Long.MIN_VALUE
    private val standardBlockedUntilByAction = mutableMapOf<Int, Long>()

    @Synchronized
    fun tryAcquire(
        nowMillis: Long,
        actionId: Int = 0,
        kind: TvRemoteActionKind = TvRemoteActionKind.STANDARD,
    ): Boolean {
        if (isGloballyBlocked(nowMillis)) return false
        if (kind == TvRemoteActionKind.TRANSITION) {
            globallyBlockedUntilMillis = saturatedAdd(nowMillis, kind.cooldownMillis)
            return true
        }

        val blockedUntilMillis = standardBlockedUntilByAction[actionId] ?: Long.MIN_VALUE
        if (nowMillis < blockedUntilMillis) return false
        standardBlockedUntilByAction[actionId] = saturatedAdd(nowMillis, kind.cooldownMillis)
        return true
    }

    @Synchronized
    fun extendBlock(
        nowMillis: Long,
        kind: TvRemoteActionKind = TvRemoteActionKind.TRANSITION,
    ) {
        globallyBlockedUntilMillis = maxOf(
            globallyBlockedUntilMillis,
            saturatedAdd(nowMillis, kind.cooldownMillis),
        )
    }

    @Synchronized
    fun isGloballyBlocked(nowMillis: Long): Boolean = nowMillis < globallyBlockedUntilMillis

    private fun saturatedAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
}
