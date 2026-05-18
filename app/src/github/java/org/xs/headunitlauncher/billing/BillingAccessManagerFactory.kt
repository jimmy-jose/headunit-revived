package org.xs.headunitlauncher.billing

import android.app.Application
import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.xs.headunitlauncher.utils.Settings

object BillingAccessManagerFactory {
    fun create(app: Application, settings: Settings): BillingAccessManager = NoOpBillingAccessManager
}

private object NoOpBillingAccessManager : BillingAccessManager {
    private val state = MutableStateFlow(AccessState.PURCHASED)

    override val isBillingEnforced: Boolean = false
    override val accessState: StateFlow<AccessState> = state

    override fun canUseAppCached(): Boolean = true
    override fun getTrialRemainingMs(nowMs: Long): Long = Long.MAX_VALUE
    override fun refreshAccessState() = Unit
    override fun restorePurchases() = Unit
    override fun launchPurchaseFlow(activity: Activity) = Unit
}
