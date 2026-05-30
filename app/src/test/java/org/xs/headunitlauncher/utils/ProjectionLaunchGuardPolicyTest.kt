package org.xs.headunitlauncher.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionLaunchGuardPolicyTest {
    @Test
    fun remainingCooldownUsesLongestActiveCooldown() {
        val remaining = ProjectionLaunchGuardPolicy.remainingCooldownMs(
            nowElapsedMs = 10_000L,
            lastPauseElapsedMs = 9_000L,
            lastDisconnectElapsedMs = 6_000L
        )

        assertEquals(2_000L, remaining)
    }

    @Test
    fun suppressesWhileCooldownActive() {
        assertTrue(
            ProjectionLaunchGuardPolicy.shouldSuppressAutoLaunch(
                nowElapsedMs = 10_000L,
                lastPauseElapsedMs = 9_500L,
                lastDisconnectElapsedMs = 0L
            )
        )
    }

    @Test
    fun zeroRemainingAfterExpiry() {
        val remaining = ProjectionLaunchGuardPolicy.remainingCooldownMs(
            nowElapsedMs = 10_000L,
            lastPauseElapsedMs = 7_000L,
            lastDisconnectElapsedMs = 3_000L
        )

        assertEquals(0L, remaining)
        assertFalse(
            ProjectionLaunchGuardPolicy.shouldSuppressAutoLaunch(
                nowElapsedMs = 10_000L,
                lastPauseElapsedMs = 7_000L,
                lastDisconnectElapsedMs = 3_000L
            )
        )
    }
}
