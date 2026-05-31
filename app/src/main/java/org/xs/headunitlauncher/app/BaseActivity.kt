package org.xs.headunitlauncher.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.xs.headunitlauncher.utils.AppThemeManager
import org.xs.headunitlauncher.utils.LocaleHelper
import org.xs.headunitlauncher.utils.Settings

/**
 * Base Activity that handles app language configuration and live theme switching.
 * All activities should extend this class to properly apply the user's language preference.
 */
open class BaseActivity : AppCompatActivity() {

    private var currentLanguage: String? = null
    private var currentAppTheme: Settings.AppTheme? = null
    private var currentNightMode: Int = 0
    private var currentUseGradientBackground: Boolean = false
    private var currentUseExtremeDarkMode: Boolean = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        currentLanguage = settings.appLanguage
        currentAppTheme = settings.appTheme
        currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        currentUseGradientBackground = settings.useGradientBackground
        currentUseExtremeDarkMode = settings.useExtremeDarkMode

        val appliedVersion = AppThemeManager.themeVersion.value
        AppThemeManager.themeVersion.observe(this) { version ->
            if (version != appliedVersion && !isDefaultHome()) {
                recreate()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val settings = Settings(this)
        val actualNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (currentLanguage != settings.appLanguage ||
            currentAppTheme != settings.appTheme ||
            currentNightMode != actualNightMode ||
            currentUseGradientBackground != settings.useGradientBackground ||
            currentUseExtremeDarkMode != settings.useExtremeDarkMode) {
            // When acting as the default home/launcher, avoid recreate() to prevent
            // memory pressure spikes that cause the system LMK to kill the process
            // on low-end devices. Instead, just update the tracked state so we don't
            // enter an infinite recreate loop next time.
            if (isDefaultHome()) {
                currentLanguage = settings.appLanguage
                currentAppTheme = settings.appTheme
                currentNightMode = actualNightMode
                currentUseGradientBackground = settings.useGradientBackground
                currentUseExtremeDarkMode = settings.useExtremeDarkMode
            } else {
                recreate()
            }
        }
    }

    private fun isDefaultHome(): Boolean {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
        }
        val resolveInfo = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName == packageName
    }
}

