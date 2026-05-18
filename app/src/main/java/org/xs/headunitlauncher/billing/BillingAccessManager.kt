package org.xs.headunitlauncher.billing

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

interface BillingAccessManager {
    val isBillingEnforced: Boolean
    val accessState: StateFlow<AccessState>

    fun canUseAppCached(): Boolean
    fun getTrialRemainingMs(nowMs: Long = System.currentTimeMillis()): Long
    fun refreshAccessState()
    fun restorePurchases()
    fun launchPurchaseFlow(activity: Activity)
}
