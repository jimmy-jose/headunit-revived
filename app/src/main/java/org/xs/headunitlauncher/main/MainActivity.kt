package org.xs.headunitlauncher.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.view.KeyEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import org.xs.headunitlauncher.App
import org.xs.headunitlauncher.R
import org.xs.headunitlauncher.aap.AapProjectionActivity
import org.xs.headunitlauncher.aap.AapService
import org.xs.headunitlauncher.app.BaseActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import org.xs.headunitlauncher.billing.AccessState
import org.xs.headunitlauncher.billing.BillingGateActivity
import org.xs.headunitlauncher.utils.AppLog
import android.content.res.Configuration
import androidx.navigation.fragment.NavHostFragment
import org.xs.headunitlauncher.utils.Settings
import org.xs.headunitlauncher.utils.LauncherUtils
import org.xs.headunitlauncher.utils.SetupWizard
import org.xs.headunitlauncher.utils.SystemUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {

    private var lastBackPressTime: Long = 0
    var keyListener: KeyListener? = null
    
    private var isOrientationReceiverRegistered = false
    private var isFinishReceiverRegistered = false
    private var isRecreateReceiverRegistered = false

    private val viewModel: MainViewModel by viewModels()

    private val finishReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == "org.xs.headunitlauncher.ACTION_FINISH_ACTIVITIES") {
                AppLog.i("MainActivity: Received finish request. Closing.")
                finishAffinity()
            }
        }
    }

    private val recreateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RECREATE_MAIN) {
                AppLog.i("MainActivity: Received recreate request. Recreating.")
                try {
                    recreate()
                } catch (e: Exception) {
                    AppLog.e("MainActivity: Failed to recreate activity", e)
                }
            }
        }
    }

    interface KeyListener {
        fun onKeyEvent(event: KeyEvent?): Boolean
    }

    private val orientationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AapService.ACTION_ORIENTATION_CHANGED) {
                AppLog.i("MainActivity: Orientation change broadcast received. Updating.")
                requestedOrientation = Settings(this@MainActivity).screenOrientation.androidOrientation
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val settings  = Settings(newBase)
        val scale = settings.uiScaleHomePercent / 100.0f
        if (scale != 1.0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val cfg = Configuration(newBase.resources.configuration)
            val metrics = newBase.resources.displayMetrics
            cfg.densityDpi = (metrics.densityDpi * scale).toInt()
            val ctx = newBase.createConfigurationContext(cfg)
            super.attachBaseContext(ctx)
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)

        if (redirectToBillingGateIfNeeded(skipPassedIntent = false)) {
            return
        }

        logLaunchSource()

        // If an Android Auto session is active, bring the projection activity to front
        if (App.provide(this).commManager.isConnected && !App.isPiPActive) {
            AppLog.i("MainActivity: Active session detected in onCreate, bringing projection to front")
            val aapIntent = AapProjectionActivity.intent(this).apply {
                putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(aapIntent)
        }

        val mainSettings = Settings(this)
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (mainSettings.appTheme == Settings.AppTheme.EXTREME_DARK ||
            (mainSettings.useExtremeDarkMode && isNightActive)) {
            theme.applyStyle(R.style.ThemeOverlay_ExtremeDark, true)
        } else if (mainSettings.useGradientBackground) {
            theme.applyStyle(R.style.ThemeOverlay_GradientBackground, true)
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        applySafeAreaPadding()

        val appSettings = Settings(this)
        requestedOrientation = appSettings.screenOrientation.androidOrientation

        // Sync UsbAttachedActivity component state with the listen for USB devices setting.
        // This covers first install, app updates (manifest may reset component state),
        // and ensures the USB system modal only appears when the user has opted in to listen for ALL USB devices.
        lifecycleScope.launch(Dispatchers.IO) {
            Settings.setUsbAttachedActivityEnabled(applicationContext, appSettings.listenForUsbDevices)
        }

        // Start main service immediately to handle connections and wireless server
        val serviceIntent = Intent(this, AapService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        setFullscreen()

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.main_content) as androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment.navController

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLauncherHomeRoot()) {
                    moveTaskToBack(true)
                    return
                }
                if (navController.navigateUp()) {
                    return
                } else if (System.currentTimeMillis() - lastBackPressTime < 2000) {
                    finish()
                } else {
                    lastBackPressTime = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show()
                }
            }
        })

        requestPermissions()
        viewModel.register()
        handleLaunchIntent(intent)
        setupWifiDirectInfo()

        ContextCompat.registerReceiver(
            this, finishReceiver,
            android.content.IntentFilter("org.xs.headunitlauncher.ACTION_FINISH_ACTIVITIES"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isFinishReceiverRegistered = true

        // Register recreate receiver so SettingsFragment can request MainActivity recreate
        ContextCompat.registerReceiver(
            this, recreateReceiver,
            android.content.IntentFilter(ACTION_RECREATE_MAIN),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isRecreateReceiverRegistered = true

        observeBillingAccess()
    }

    private fun applySafeAreaPadding() {
        val root = findViewById<View>(R.id.root) ?: return
        val initialStart = root.paddingStart
        val initialTop = root.paddingTop
        val initialEnd = root.paddingEnd
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
            )
            view.setPadding(
                initialStart + systemBars.left,
                initialTop + systemBars.top,
                initialEnd + systemBars.right,
                initialBottom + systemBars.bottom
            )
            insets
        }
    }

    private fun setupWifiDirectInfo() {
        val tvInfo = findViewById<android.widget.TextView>(R.id.wifi_direct_info)
        val settings = Settings(this)

        lifecycleScope.launch {
            AapService.wifiDirectName.collectLatest { name ->
                val isHelperMode = settings.wifiConnectionMode == 2
                if (isHelperMode && name != null) {
                    tvInfo.text = "WiFi Direct: $name"
                    tvInfo.visibility = View.VISIBLE
                } else {
                    tvInfo.visibility = View.GONE
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun logLaunchSource() {
        val source = intent?.getStringExtra(EXTRA_LAUNCH_SOURCE)
        if (source != null) {
            AppLog.i("App launched via: $source")
            return
        }

        val referrer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            referrer?.toString()
        } else null

        val isLauncherTap = intent?.action == Intent.ACTION_MAIN &&
                intent.hasCategory(Intent.CATEGORY_LAUNCHER)

        if (isLauncherTap) {
            AppLog.i("App launched by user tap (referrer: ${referrer ?: "none"})")
        } else if (referrer != null) {
            AppLog.i("App launched by third party: $referrer (action: ${intent?.action})")
        } else {
            AppLog.i("App launched, source unknown (action: ${intent?.action})")
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return

        AppLog.i("MainActivity: Processing launch intent: ${intent.action}, data: ${intent.data}")

        val intentData = intent.data
        val intentAction = intent.action

        if (intentAction == "org.xs.headunitlauncher.ACTION_EXIT") {
            AppLog.i("MainActivity: Received exit action")
            val exitIntent = Intent(this, AapService::class.java).apply {
                this.action = AapService.ACTION_STOP_SERVICE
            }
            ContextCompat.startForegroundService(this, exitIntent)
            finishAffinity()
            return
        }

        if (intentAction == AapService.ACTION_START_SELF_MODE || 
           (intentData?.scheme == "headunit" && intentData.host == "selfmode")) {
            AppLog.i("MainActivity: Forced self-mode start requested")
            HomeFragment.forceSelfModeLaunch = true
            val selfModeIntent = Intent(this, AapService::class.java).apply {
                this.action = AapService.ACTION_START_SELF_MODE
            }
            ContextCompat.startForegroundService(this, selfModeIntent)
        }

        if (intent.action == Intent.ACTION_VIEW) {
            if (intentData?.scheme == "headunit" && intentData.host == "connect") {
                val ip = intentData.getQueryParameter("ip")
                if (!ip.isNullOrEmpty()) {
                    AppLog.i("Received connect intent for IP: $ip")
                    ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
                        action = AapService.ACTION_CONNECT_SOCKET
                    })
                    lifecycleScope.launch(Dispatchers.IO) { App.provide(this@MainActivity).commManager.connect(ip, 5277) }
                } else {
                    AppLog.i("Received connect intent without IP -> triggering last session auto-connect")
                    val autoIntent = Intent(this, AapService::class.java).apply {
                        action = AapService.ACTION_CHECK_USB
                    }
                    ContextCompat.startForegroundService(this, autoIntent)
                }
            } else if (intentData?.scheme == "headunit" && intentData.host == "disconnect") {
                AppLog.i("Received disconnect intent")
                val stopIntent = Intent(this, AapService::class.java).apply {
                    action = AapService.ACTION_DISCONNECT
                }
                ContextCompat.startForegroundService(this, stopIntent)
            } else if (intentData?.scheme == "headunit" && intentData.host == "exit") {
                AppLog.i("Received full exit intent via deep link")
                val exitIntent = Intent(this, AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(this, exitIntent)
                finishAffinity()
            }
        }
    }

    private fun isLauncherHomeRoot(): Boolean {
        if (!LauncherUtils.isDefaultHomeApp(this)) return false
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.main_content) as? NavHostFragment
        val currentDestinationId = navHostFragment?.navController?.currentDestination?.id
        return currentDestinationId == R.id.homeFragment
    }

    private fun requestPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Filter out permissions that are already granted
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            AppLog.i("Requesting missing permissions: $permissionsToRequest")
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                permissionRequestCode
            )
        } else {
            AppLog.d("All required permissions already granted.")
        }
    }

    private fun setFullscreen() {
        val root = findViewById<View>(R.id.root)
        val appSettings = Settings(this)
        SystemUI.apply(window, root, appSettings.fullscreenMode)
    }

    override fun onResume() {
        super.onResume()

        if (redirectToBillingGateIfNeeded(skipPassedIntent = true)) {
            return
        }

        setFullscreen()
        App.provide(this).billingAccessManager.refreshAccessState()

        checkSetupFlow()

        requestedOrientation = Settings(this).screenOrientation.androidOrientation
        if (!isOrientationReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                orientationReceiver,
                android.content.IntentFilter(AapService.ACTION_ORIENTATION_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isOrientationReceiverRegistered = true
        }

        // If an Android Auto session is active, bring the projection activity to front
        if (App.provide(this).commManager.isConnected && !App.isPiPActive && !AapProjectionActivity.isForeground) {
            AppLog.i("MainActivity: Active session detected, bringing projection to front")
            val aapIntent = AapProjectionActivity.intent(this).apply {
                putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(aapIntent)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isOrientationReceiverRegistered) {
            unregisterReceiver(orientationReceiver)
            isOrientationReceiverRegistered = false
        }
    }

    fun checkSetupFlow() {
        val appSettings = Settings(this)
        if (!appSettings.hasAcceptedDisclaimer) {
            SafetyDisclaimerDialog.show(supportFragmentManager)
            return
        }

        if (!appSettings.hasCompletedSetupWizard) {
            SetupWizard(this) {
                // Refresh activity after setup
                recreate()
            }.start()
        }

        if (appSettings.shouldPromptForLauncher && !LauncherUtils.isDefaultHomeApp(this)) {
            LauncherPromptDialog.show(supportFragmentManager)
        }
    }

    private fun redirectToBillingGateIfNeeded(skipPassedIntent: Boolean): Boolean {
        val accessManager = App.provide(this).billingAccessManager
        if (!accessManager.isBillingEnforced) return false
        if (!skipPassedIntent && BillingGateActivity.wasGatePassed(intent)) return false
        if (accessManager.canUseAppCached()) return false

        val forwardIntent = Intent(intent ?: Intent(this, MainActivity::class.java)).apply {
            setClass(this@MainActivity, MainActivity::class.java)
        }
        startActivity(BillingGateActivity.createIntent(this, forwardIntent))
        finish()
        return true
    }

    private fun observeBillingAccess() {
        val accessManager = App.provide(this).billingAccessManager
        if (!accessManager.isBillingEnforced) return

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                accessManager.accessState.collectLatest { state ->
                    if ((state == AccessState.LOCKED || state == AccessState.ERROR_RETRYABLE) &&
                        !accessManager.canUseAppCached()) {
                        redirectToBillingGateIfNeeded(skipPassedIntent = true)
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setFullscreen()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        AppLog.i("dispatchKeyEvent: keyCode=%d, action=%d", event.keyCode, event.action)
        
        // Always give the KeymapFragment (if active) a chance to see the key
        val handled = keyListener?.onKeyEvent(event) ?: false
        
        // If the key was handled by our listener (e.g. in KeymapFragment), stop here
        if (handled) return true
        
        // Otherwise continue with standard handling
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishReceiverRegistered) {
            unregisterReceiver(finishReceiver)
            isFinishReceiverRegistered = false
        }
        if (isRecreateReceiverRegistered) {
            unregisterReceiver(recreateReceiver)
            isRecreateReceiverRegistered = false
        }
        if (isFinishing) {
            AppLog.i("MainActivity finishing, resetting auto-start flag.")
            HomeFragment.resetAutoStart()
        }
    }

    companion object {
        private const val permissionRequestCode = 97
        const val EXTRA_LAUNCH_SOURCE = "launch_source"
        const val ACTION_RECREATE_MAIN = "org.xs.headunitlauncher.ACTION_RECREATE_MAIN"
    }
}
