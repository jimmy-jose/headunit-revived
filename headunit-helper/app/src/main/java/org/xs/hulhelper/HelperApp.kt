package org.xs.hulhelper

import android.app.Application
import com.google.android.material.color.DynamicColors
import org.xs.hulhelper.utils.CrashReportStore

class HelperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReportStore.install(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
