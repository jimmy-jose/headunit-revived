package org.xs.headunitlauncher.billing

import android.content.Context
import android.content.Intent
import org.xs.headunitlauncher.App
import org.xs.headunitlauncher.main.MainActivity
import org.xs.headunitlauncher.utils.AppLog

object BillingAccessEnforcer {

    fun canUseAppCached(context: Context): Boolean {
        return App.provide(context).billingAccessManager.canUseAppCached()
    }

    fun ensureAccessOrLaunchGate(
        context: Context,
        launchSource: String,
        targetIntent: Intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, launchSource)
        }
    ): Boolean {
        if (canUseAppCached(context)) {
            return true
        }

        try {
            context.startActivity(BillingGateActivity.createIntent(context, targetIntent))
            AppLog.i("BillingAccessEnforcer: launched billing gate for blocked source=$launchSource")
        } catch (e: Exception) {
            AppLog.w("BillingAccessEnforcer: could not launch billing gate for $launchSource: ${e.message}")
        }
        return false
    }
}
