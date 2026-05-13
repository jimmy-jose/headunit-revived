package org.xs.headunitlauncher.utils

import android.content.IntentFilter
import org.xs.headunitlauncher.contract.KeyIntent

object IntentFilters {
    val keyEvent = IntentFilter(KeyIntent.action)
}