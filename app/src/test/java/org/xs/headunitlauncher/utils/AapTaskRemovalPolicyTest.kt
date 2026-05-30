package org.xs.headunitlauncher.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AapTaskRemovalPolicyTest {
    @Test
    fun defaultHomeWithActiveSessionRestartsService() {
        val shouldRestart = AapTaskRemovalPolicy.shouldRestartServiceOnTaskRemoved(
            isDestroying = false,
            isDefaultHome = true,
            hasActiveSession = true
        )

        assertTrue(shouldRestart)
    }

    @Test
    fun defaultHomeWithoutActiveSessionDoesNotRestartService() {
        val shouldRestart = AapTaskRemovalPolicy.shouldRestartServiceOnTaskRemoved(
            isDestroying = false,
            isDefaultHome = true,
            hasActiveSession = false
        )

        assertFalse(shouldRestart)
    }

    @Test
    fun destroyingServiceDoesNotRestart() {
        val shouldRestart = AapTaskRemovalPolicy.shouldRestartServiceOnTaskRemoved(
            isDestroying = true,
            isDefaultHome = true,
            hasActiveSession = true
        )

        assertFalse(shouldRestart)
    }
}
