package org.xs.headunitlauncher.utils

object AapTaskRemovalPolicy {
    fun hasActiveSession(connectionStateActive: Boolean, selfMode: Boolean): Boolean {
        return connectionStateActive || selfMode
    }

    fun shouldRestartServiceOnTaskRemoved(
        isDestroying: Boolean,
        isDefaultHome: Boolean,
        hasActiveSession: Boolean
    ): Boolean {
        // Default-home launcher tasks are removable during normal Home/projection churn.
        // An active AA session should still survive that task removal.
        return !isDestroying && hasActiveSession
    }
}
