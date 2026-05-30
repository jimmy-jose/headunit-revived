package org.xs.headunitlauncher.utils

object ProjectionLaunchGuardPolicy {
    const val PAUSE_RELAUNCH_COOLDOWN_MS = 2_000L
    const val DISCONNECT_RELAUNCH_COOLDOWN_MS = 6_000L

    fun remainingCooldownMs(
        nowElapsedMs: Long,
        lastPauseElapsedMs: Long,
        lastDisconnectElapsedMs: Long
    ): Long {
        return maxOf(
            remainingFor(nowElapsedMs, lastPauseElapsedMs, PAUSE_RELAUNCH_COOLDOWN_MS),
            remainingFor(nowElapsedMs, lastDisconnectElapsedMs, DISCONNECT_RELAUNCH_COOLDOWN_MS)
        )
    }

    fun shouldSuppressAutoLaunch(
        nowElapsedMs: Long,
        lastPauseElapsedMs: Long,
        lastDisconnectElapsedMs: Long
    ): Boolean {
        return remainingCooldownMs(nowElapsedMs, lastPauseElapsedMs, lastDisconnectElapsedMs) > 0L
    }

    private fun remainingFor(nowElapsedMs: Long, lastEventElapsedMs: Long, cooldownMs: Long): Long {
        if (lastEventElapsedMs <= 0L) return 0L
        return (cooldownMs - (nowElapsedMs - lastEventElapsedMs)).coerceAtLeast(0L)
    }
}
