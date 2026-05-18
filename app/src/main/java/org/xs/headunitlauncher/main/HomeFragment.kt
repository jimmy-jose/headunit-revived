package org.xs.headunitlauncher.main

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.view.inputmethod.InputMethodManager
import android.os.Bundle
import android.graphics.Color
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.graphics.drawable.Drawable
import android.widget.*
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.net.VpnService
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.xs.headunitlauncher.App
import org.xs.headunitlauncher.R
import org.xs.headunitlauncher.aap.AapProjectionActivity
import org.xs.headunitlauncher.aap.AapService
import org.xs.headunitlauncher.connection.NearbyManager
import org.xs.headunitlauncher.connection.UsbDeviceCompat
import android.content.res.Configuration
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import org.xs.headunitlauncher.utils.AppLog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import org.xs.headunitlauncher.utils.LauncherUtils
import org.xs.headunitlauncher.utils.CrashReportStore
import org.xs.headunitlauncher.utils.Settings
import org.xs.headunitlauncher.utils.VpnControl
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HomeFragment : Fragment() {

    private val commManager get() = App.provide(requireContext()).commManager

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            AppLog.i("VPN permission granted. Starting DummyVpnService and Self Mode.")
            VpnControl.startVpn(requireContext());
            startSelfModeInternal()
        } else {
            AppLog.w("VPN permission denied. Offline Self Mode might fail.")
            Toast.makeText(requireContext(), getString(R.string.failed_start_android_auto), Toast.LENGTH_LONG).show()
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            showNativeAaDeviceSelector()
        } else {
            Toast.makeText(requireContext(), R.string.bt_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private lateinit var self_mode_button: Button
    private lateinit var usb: Button
    private lateinit var settings: Button
    private lateinit var wifi: Button
    private lateinit var connectionInfoButton: ImageButton
    private lateinit var wifi_text_view: TextView
    private var crashReportContainer: View? = null
    private var crashReportText: TextView? = null
    private var crashReportIgnoreButton: Button? = null
    private var nativeWirelessWarningContainer: View? = null
    private var nativeWirelessWarningText: TextView? = null
    private var nativeWirelessSwitchButton: Button? = null
    private var nativeWirelessTutorialButton: Button? = null
    private var nativeWirelessHideButton: Button? = null
    private lateinit var exitButton: Button
    private var launcherSetupContainer: View? = null
    private lateinit var self_mode_text: TextView
    private lateinit var homeAppsRecyclerView: RecyclerView
    private lateinit var homeAppsEmptyView: TextView
    private lateinit var clockTimeText: TextView
    private lateinit var clockDayText: TextView
    private lateinit var clockDateText: TextView
    private lateinit var appDrawerSheet: View
    private lateinit var appDrawerHandle: View
    private lateinit var appDrawerContent: View
    private lateinit var appDrawerChevron: ImageView
    private lateinit var appDrawerSearchInput: TextInputEditText
    private lateinit var homeAppsAdapter: HomeAppsAdapter
    private lateinit var appDrawerBehavior: BottomSheetBehavior<View>
    private var clockJob: Job? = null
    private var hasAttemptedAutoConnect = false
    private var hasAttemptedSingleUsbAutoConnect = false
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null

    private fun updateWifiButtonFeedback(scanning: Boolean) {
        if (scanning) {
            wifi_text_view.text = getString(R.string.searching)
            wifi.alpha = 0.6f
        } else {
            wifi_text_view.text = getString(R.string.wifi)
            wifi.alpha = 1.0f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        self_mode_button = view.findViewById(R.id.self_mode_button)
        usb = view.findViewById(R.id.usb_button)
        settings = view.findViewById(R.id.settings_button)
        wifi = view.findViewById(R.id.wifi_button)
        connectionInfoButton = view.findViewById(R.id.connection_info_button)
        wifi_text_view = view.findViewById(R.id.wifi_text)
        crashReportContainer = view.findViewById(R.id.crash_report_container)
        crashReportText = view.findViewById(R.id.crash_report_text)
        crashReportIgnoreButton = view.findViewById(R.id.crash_report_ignore_button)
        nativeWirelessWarningContainer = view.findViewById(R.id.native_wireless_warning_container)
        nativeWirelessWarningText = view.findViewById(R.id.native_wireless_warning_text)
        nativeWirelessSwitchButton = view.findViewById(R.id.native_wireless_switch_button)
        nativeWirelessTutorialButton = view.findViewById(R.id.native_wireless_tutorial_button)
        nativeWirelessHideButton = view.findViewById(R.id.native_wireless_hide_button)
        exitButton = view.findViewById(R.id.exit_button)
        launcherSetupContainer = view.findViewById(R.id.launcher_setup_container)
        self_mode_text = view.findViewById(R.id.self_mode_text)
        clockTimeText = view.findViewById(R.id.clock_time_text)
        clockDayText = view.findViewById(R.id.clock_day_text)
        clockDateText = view.findViewById(R.id.clock_date_text)
        appDrawerSheet = view.findViewById(R.id.app_drawer_sheet)
        appDrawerHandle = view.findViewById(R.id.app_drawer_handle)
        appDrawerContent = view.findViewById(R.id.app_drawer_content)
        appDrawerChevron = view.findViewById(R.id.app_drawer_chevron)
        appDrawerSearchInput = view.findViewById(R.id.app_drawer_search_input)
        homeAppsRecyclerView = view.findViewById(R.id.home_apps_recycler)
        homeAppsEmptyView = view.findViewById(R.id.home_apps_empty_text)

        homeAppsAdapter = HomeAppsAdapter { app ->
            try {
                LauncherUtils.launchApp(requireContext(), app.componentName)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.failed_open_app, Toast.LENGTH_SHORT).show()
            }
        }
        homeAppsRecyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        homeAppsRecyclerView.adapter = homeAppsAdapter
        homeAppsRecyclerView.isNestedScrollingEnabled = true

        appDrawerBehavior = BottomSheetBehavior.from(appDrawerSheet).apply {
            isFitToContents = true
            skipCollapsed = false
            isHideable = false
            state = BottomSheetBehavior.STATE_COLLAPSED
        }
        appDrawerBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HALF_EXPANDED) {
                    appDrawerBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                    return
                }
                updateDrawerUi(isExpanded = newState == BottomSheetBehavior.STATE_EXPANDED)
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                appDrawerSheet.alpha = 0.82f + (0.18f * slideOffset.coerceIn(0f, 1f))
                updateDrawerBackground(slideOffset >= 0.98f)
                appDrawerChevron.rotation = 180f * slideOffset.coerceIn(0f, 1f)
            }
        })
        applySystemInsets()

        setupListeners()
        updateProjectionButtonText()
        updateLauncherUi()
        updateCrashReportBanner()
        updateNativeWirelessWarning()
        loadHomeApps()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commManager.connectionState.collect { updateProjectionButtonText() }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AapService.scanningState.collect { updateWifiButtonFeedback(it) }
            }
        }

        clockJob?.cancel()
        clockJob = viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    updateClockWidget()
                    delay(millisecondsUntilNextMinute())
                }
            }
        }

        val appSettings = App.provide(requireContext()).settings

        if (appSettings.autoStartOnScreenOn || appSettings.autoStartOnBoot) {
            ContextCompat.startForegroundService(requireContext(),
                Intent(requireContext(), AapService::class.java))
        }

        appDrawerSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                homeAppsAdapter.filter(s?.toString().orEmpty())
                updateHomeAppsEmptyState()
            }
        })

        for (methodId in appSettings.autoConnectPriorityOrder) {
            if (commManager.isConnected) break
            when (methodId) {
                Settings.AUTO_CONNECT_LAST_SESSION -> {
                    if (appSettings.autoConnectLastSession && !hasAttemptedAutoConnect && !commManager.isConnected) {
                        hasAttemptedAutoConnect = true
                        attemptAutoConnect()
                    }
                }
                Settings.AUTO_CONNECT_SELF_MODE -> {
                    if ((appSettings.autoStartSelfMode || forceSelfModeLaunch) && !hasAutoStarted && !commManager.isConnected) {
                        hasAutoStarted = true
                        forceSelfModeLaunch = false // Reset once processed
                        startSelfMode()
                    }
                }
                Settings.AUTO_CONNECT_SINGLE_USB -> {
                    if (appSettings.autoConnectSingleUsbDevice && !hasAttemptedSingleUsbAutoConnect && !commManager.isConnected) {
                        hasAttemptedSingleUsbAutoConnect = true
                        attemptSingleUsbAutoConnect()
                    }
                }
            }
        }
    }

    private fun startSelfModeInternal() {
        AapService.selfMode = true
        val intent = Intent(requireContext(), AapService::class.java)
        intent.action = AapService.ACTION_START_SELF_MODE
        ContextCompat.startForegroundService(requireContext(), intent)
        AppLog.i("Auto start selfmode")
    }

    private fun startSelfMode() {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork
        } else null

        if (activeNetwork == null && VpnControl.isVpnAvailable()) {
            AppLog.i("Device is offline. Preparing Dummy VPN for Self Mode.")
            val vpnIntent = VpnService.prepare(requireContext())
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
                return
            } else {
                AppLog.i("VPN permission already granted. Starting VPN service.")
                VpnControl.startVpn(requireContext());
            }
        } else if (activeNetwork == null) {
            AppLog.i("Device is offline and VPN is not available in this build. Self Mode may fail.")
        }
        startSelfModeInternal()
    }

    private fun attemptAutoConnect() {
        val appSettings = App.provide(requireContext()).settings

        // [FIX] Skip manual WiFi connection if Native AA is selected.
        // Native AA handles its own handshake via Bluetooth/P2P.
        if (appSettings.wifiConnectionMode == 3) {
            AppLog.i("HomeFragment: Native AA mode active. Skipping manual auto-connect attempt.")
            return
        }

        if (!appSettings.autoConnectLastSession ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) {
            return
        }

        val connectionType = appSettings.lastConnectionType
        if (connectionType.isEmpty()) {
            AppLog.i("Auto-connect: No last session to reconnect to")
            return
        }

        when (connectionType) {
            Settings.CONNECTION_TYPE_WIFI -> {
                val ip = appSettings.lastConnectionIp
                if (ip.isNotEmpty()) {
                    AppLog.i("Auto-connect: Attempting WiFi connection to $ip")
                    Toast.makeText(requireContext(), getString(R.string.auto_connecting_to, ip), Toast.LENGTH_SHORT).show()
                    val ctx = requireContext()
                    lifecycleScope.launch(Dispatchers.IO) { App.provide(ctx).commManager.connect(ip, 5277) }
                    ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_CONNECT_SOCKET
                    })
                }
            }
            Settings.CONNECTION_TYPE_USB -> {
                val lastUsbDevice = appSettings.lastConnectionUsbDevice
                if (lastUsbDevice.isNotEmpty()) {
                    val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
                    val matchingDevice = usbManager.deviceList.values.find { device ->
                        UsbDeviceCompat.getUniqueName(device) == lastUsbDevice
                    }
                    if (matchingDevice != null && usbManager.hasPermission(matchingDevice)) {
                        AppLog.i("Auto-connect: Attempting USB connection to $lastUsbDevice")
                        Toast.makeText(requireContext(), getString(R.string.auto_connecting_usb), Toast.LENGTH_SHORT).show()
                        ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_CHECK_USB
                        })
                    } else {
                        AppLog.i("Auto-connect: USB device $lastUsbDevice not found or no permission")
                    }
                }
            }
            Settings.CONNECTION_TYPE_NEARBY -> {
                AppLog.i("Auto-connect: Last session was via Google Nearby. AapService will handle discovery.")
                // No manual connect(ip) needed, NearbyManager in AapService manages this automatically on start.
            }
        }
    }

    private fun attemptSingleUsbAutoConnect() {
        val appSettings = App.provide(requireContext()).settings
        if (!appSettings.autoConnectSingleUsbDevice ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) return

        AppLog.i("HomeFragment: Requesting single-USB auto-connect via AapService")
        ContextCompat.startForegroundService(requireContext(),
            Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
            })
    }

    private val originalBackgrounds = mapOf(
        R.id.self_mode_button to R.drawable.gradient_teal,
        R.id.usb_button to R.drawable.gradient_rust,
        R.id.wifi_button to R.drawable.gradient_rose,
        R.id.settings_button to R.drawable.gradient_olive
    )

    private fun applyMonochromeStyle() {
        val monochromeBackground = ContextCompat.getDrawable(requireContext(), R.drawable.gradient_monochrome)
        val grayTint = ColorStateList.valueOf(0xFF808080.toInt())
        listOf(self_mode_button, usb, wifi, settings).forEach { button ->
            button.background = monochromeBackground?.constantState?.newDrawable()?.mutate()
            (button as? com.google.android.material.button.MaterialButton)?.iconTint = grayTint
        }
    }

    private fun restoreOriginalStyle() {
        val whiteTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        val buttons = listOf(self_mode_button, usb, wifi, settings)
        val ids = listOf(R.id.self_mode_button, R.id.usb_button, R.id.wifi_button, R.id.settings_button)
        buttons.zip(ids).forEach { (button, id) ->
            originalBackgrounds[id]?.let { drawableRes ->
                button.background = ContextCompat.getDrawable(requireContext(), drawableRes)
            }
            (button as? com.google.android.material.button.MaterialButton)?.iconTint = whiteTint
        }
    }

    private fun updateButtonStyle() {
        val appSettings = App.provide(requireContext()).settings
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isDarkTheme = appSettings.appTheme == Settings.AppTheme.DARK ||
                          appSettings.appTheme == Settings.AppTheme.EXTREME_DARK ||
                          isNightActive
        if (isDarkTheme && appSettings.monochromeIcons) {
            applyMonochromeStyle()
        } else {
            restoreOriginalStyle()
        }
    }

    private fun setupListeners() {
        exitButton.setOnClickListener {
            if (LauncherUtils.isDefaultHomeApp(requireContext())) {
                val controller = findNavController()
                if (controller.currentDestination?.id == R.id.homeFragment) {
                    controller.navigate(R.id.action_homeFragment_to_appLauncherListFragment)
                }
                return@setOnClickListener
            }

            val appSettings = App.provide(requireContext()).settings
            val keepServiceAlive = appSettings.autoStartOnBoot ||
                appSettings.autoStartOnScreenOn ||
                (appSettings.autoStartOnUsb && appSettings.reopenOnReconnection)
            if (keepServiceAlive) {
                val disconnectIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_DISCONNECT
                }
                ContextCompat.startForegroundService(requireContext(), disconnectIntent)
            } else {
                val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
            }
            requireActivity().finishAffinity()
        }

        self_mode_button.setOnClickListener {
            if (commManager.isConnected) {
                val aapIntent = Intent(requireContext(), AapProjectionActivity::class.java)
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                startActivity(aapIntent)
            } else {
                startSelfMode()
            }
        }

        usb.setOnClickListener {
            val controller = findNavController()
            if (controller.currentDestination?.id == R.id.homeFragment) {
                controller.navigate(R.id.action_homeFragment_to_usbListFragment)
            }
        }

        settings.setOnClickListener {
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            startActivity(intent)
        }

        connectionInfoButton.setOnClickListener {
            showConnectionInfoDialog()
        }

        crashReportContainer?.setOnClickListener {
            val shared = CrashReportStore.sharePendingReport(requireContext())
            if (!shared) {
                Toast.makeText(requireContext(), R.string.crash_report_missing, Toast.LENGTH_SHORT).show()
                updateCrashReportBanner()
            }
        }

        crashReportIgnoreButton?.setOnClickListener {
            CrashReportStore.ignorePendingReport(requireContext())
            updateCrashReportBanner()
            Toast.makeText(requireContext(), R.string.crash_report_ignored, Toast.LENGTH_SHORT).show()
        }

        appDrawerHandle.setOnClickListener {
            toggleAppDrawer()
        }

        wifi.setOnClickListener {
            val mode = App.provide(requireContext()).settings.wifiConnectionMode
            when (mode) {
                2 -> { // Helper (Wireless Launcher)
                    if (commManager.isConnected) {
                        // Already connected
                    } else {
                        val strategy = App.provide(requireContext()).settings.helperConnectionStrategy
                        if (strategy == 2) {
                            // Nearby Devices — show live discovery dialog
                            showNearbyDeviceSelector()
                        } else if (AapService.scanningState.value) {
                            Toast.makeText(requireContext(), getString(R.string.already_searching_phone), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.searching_phone), Toast.LENGTH_SHORT).show()
                            val intent = Intent(requireContext(), AapService::class.java).apply {
                                action = AapService.ACTION_START_WIRELESS_SCAN
                            }
                            ContextCompat.startForegroundService(requireContext(), intent)
                        }
                    }
                }
                3 -> { // Native AA
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                        ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        showNativeAaDeviceSelector()
                    }
                }
            }
        }

        wifi.setOnLongClickListener {
            true
        }

        nativeWirelessSwitchButton?.setOnClickListener {
            switchToWirelessHelper()
        }

        nativeWirelessTutorialButton?.setOnClickListener {
            val controller = findNavController()
            if (controller.currentDestination?.id == R.id.homeFragment) {
                controller.navigate(R.id.action_homeFragment_to_wirelessHelperTutorialFragment)
            }
        }

        nativeWirelessHideButton?.setOnClickListener {
            val appSettings = App.provide(requireContext()).settings
            appSettings.hideNativeWirelessWarning = true
            appSettings.commit()
            updateNativeWirelessWarning()
        }
    }

    private fun updateProjectionButtonText() {
        if (commManager.isConnected) {
            self_mode_text.text = getString(R.string.to_android_auto)
        } else {
            self_mode_text.text = getString(R.string.self_mode)
        }
    }

    private fun showConnectionInfoDialog() {
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.connection_info)
            .setMessage(getString(R.string.connection_options_explainer))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        hideKeyboard()
        AppLog.i("HomeFragment: onResume. isConnected=${commManager.isConnected}")
        updateProjectionButtonText()
        updateLauncherUi()
        updateCrashReportBanner()
        loadHomeApps()
        updateDrawerUi(appDrawerBehavior.state == BottomSheetBehavior.STATE_EXPANDED)
        updateNativeWirelessWarning()
        updateButtonStyle()
        updateTextColors()
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val targetView = view ?: requireActivity().currentFocus ?: return
        imm?.hideSoftInputFromWindow(targetView.windowToken, 0)
        targetView.clearFocus()
    }

    private fun updateNativeWirelessWarning() {
        val appSettings = App.provide(requireContext()).settings
        val shouldShow = appSettings.wifiConnectionMode == 3 &&
            appSettings.nativeWirelessUnsupported &&
            !appSettings.hideNativeWirelessWarning
        nativeWirelessWarningContainer?.visibility = if (shouldShow) View.VISIBLE else View.GONE
        nativeWirelessWarningText?.text = getString(R.string.native_wireless_home_warning)
    }

    private fun updateCrashReportBanner() {
        val report = CrashReportStore.getPendingReport(requireContext())
        crashReportContainer?.visibility = if (report == null) View.GONE else View.VISIBLE
        crashReportText?.text = if (report == null) {
            ""
        } else {
            getString(
                R.string.crash_report_ready_message,
                CrashReportStore.formatTimestamp(report.capturedAtMillis),
                report.summary
            )
        }
    }

    private fun switchToWirelessHelper() {
        val appSettings = App.provide(requireContext()).settings
        if (appSettings.wifiConnectionMode == 2) {
            updateNativeWirelessWarning()
            return
        }

        appSettings.wifiConnectionMode = 2
        appSettings.commit()

        val intent = Intent(requireContext(), AapService::class.java).apply {
            action = AapService.ACTION_START_WIRELESS
        }
        requireContext().startService(intent)

        updateNativeWirelessWarning()
        Toast.makeText(requireContext(), R.string.switched_to_wireless_helper, Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        activeDialog?.dismiss()
        activeDialog = null
    }

    private fun showNativeAaDeviceSelector() {
        val adapter = if (Build.VERSION.SDK_INT >= 18) {
            (requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }

        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(requireContext(), getString(R.string.bt_not_enabled), Toast.LENGTH_SHORT).show()
            return
        }

        val bondedDevices = adapter.bondedDevices?.toList() ?: emptyList()
        if (bondedDevices.isEmpty()) {
            Toast.makeText(requireContext(), "No paired Bluetooth devices found", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceNames = bondedDevices.map { it.name ?: "Unknown Device" }.toTypedArray()
        
        
        activeDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.select_bt_device)
            .setItems(deviceNames) { _, which ->
                val device = bondedDevices[which]
                AppLog.i("HomeFragment: Manually selected ${device.name} for Native-AA poke")
                
                val intent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_NATIVE_AA_POKE
                    putExtra(AapService.EXTRA_MAC, device.address)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
                Toast.makeText(requireContext(), "Searching for ${device.name}...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNearbyDeviceSelector() {
        // Ensure NearbyManager discovery is running via AapService
        ContextCompat.startForegroundService(requireContext(),
            Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_START_WIRELESS_SCAN
            })

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_nearby_selection, null)
        val listContainer = dialogView.findViewById<View>(R.id.listContainer)
        val deviceListView = dialogView.findViewById<ListView>(R.id.deviceList)
        val searchingText = dialogView.findViewById<TextView>(R.id.searchingText)
        val connectingContainer = dialogView.findViewById<View>(R.id.connectingContainer)
        val connectingText = dialogView.findViewById<TextView>(R.id.connectingText)
        val connectionProgress = dialogView.findViewById<ProgressBar>(R.id.connectionProgress)

        // Ensure the loading spinner is visible in both Light and Dark modes by forcing our brand color.
        val brandTeal = ContextCompat.getColor(requireContext(), R.color.brand_teal)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            connectionProgress.indeterminateTintList = ColorStateList.valueOf(brandTeal)
            connectionProgress.indeterminateTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        } else {
            @Suppress("DEPRECATION")
            connectionProgress.indeterminateDrawable?.setColorFilter(brandTeal, android.graphics.PorterDuff.Mode.SRC_IN)
        }

        // Custom adapter to handle rounded backgrounds like in USB/Network lists
        val listAdapter = object : ArrayAdapter<NearbyManager.DiscoveredEndpoint>(requireContext(), R.layout.list_item_nearby) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_nearby, parent, false)
                val endpoint = getItem(position)
                view.findViewById<TextView>(R.id.deviceName).text = endpoint?.name ?: "Unknown"

                // Apply rounded backgrounds based on position
                val isTop = position == 0
                val isBottom = position == count - 1
                val bgRes = when {
                    isTop && isBottom -> R.drawable.bg_setting_single
                    isTop -> R.drawable.bg_setting_top
                    isBottom -> R.drawable.bg_setting_bottom
                    else -> R.drawable.bg_setting_middle
                }
                view.setBackgroundResource(bgRes)
                return view
            }
        }
        deviceListView.adapter = listAdapter

        var collectJob: Job? = null

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(getString(R.string.searching)) // Initial title
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { 
                collectJob?.cancel()
                if (activeDialog == it) activeDialog = null
            }
            .create()
        
        activeDialog = dialog

        deviceListView.setOnItemClickListener { _, _, which, _ ->
            val endpoints = NearbyManager.discoveredEndpoints.value
            if (which < endpoints.size) {
                val endpoint = endpoints[which]
                AppLog.i("HomeFragment: Selected Nearby device: ${endpoint.name} (${endpoint.id})")
                
                // UI Switch: Hide list, show connecting spinner
                listContainer.visibility = View.GONE
                connectingContainer.visibility = View.VISIBLE
                connectingText.text = getString(R.string.connecting_to_nearby, endpoint.name)
                
                // Allow the user to see the progress
                dialog.setCancelable(false) 

                val intent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_NEARBY_CONNECT
                    putExtra(AapService.EXTRA_ENDPOINT_ID, endpoint.id)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
            }
        }

        dialog.show()

        // Live-update the dialog list as endpoints are discovered
        collectJob = viewLifecycleOwner.lifecycleScope.launch {
            NearbyManager.discoveredEndpoints.collect { endpoints ->
                listAdapter.clear()
                listAdapter.addAll(endpoints)
                listAdapter.notifyDataSetChanged()
                
                if (endpoints.isEmpty()) {
                    dialog.setTitle(getString(R.string.searching))
                    searchingText.visibility = View.GONE
                } else {
                    dialog.setTitle(getString(R.string.nearby_device_found))
                    searchingText.visibility = View.VISIBLE
                    searchingText.text = getString(R.string.select_nearby_device) + " (${endpoints.size})"
                }
            }
        }
    }

    private fun updateTextColors() {
        val appSettings = App.provide(requireContext()).settings
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isLightMode = nightModeFlags != Configuration.UI_MODE_NIGHT_YES

        val labelViews = listOf(self_mode_text, wifi_text_view,
            view?.findViewById<TextView>(R.id.usb_text),
            view?.findViewById<TextView>(R.id.settings_text))

        if (appSettings.useGradientBackground && isLightMode) {
            val darkColor = Color.parseColor("#1a1a1a")
            labelViews.filterNotNull().forEach { tv ->
                tv.setTextColor(darkColor)
                tv.setShadowLayer(2f, 0f, 0f, Color.WHITE)
            }
        } else {
            val lightColor = Color.parseColor("#f7f7f7")
            labelViews.filterNotNull().forEach { tv ->
                tv.setTextColor(lightColor)
                tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }

        exitButton.setTextColor(Color.WHITE)
    }

    private fun updateLauncherUi() {
        val isDefaultLauncher = LauncherUtils.isDefaultHomeApp(requireContext())
        launcherSetupContainer?.visibility = View.GONE
        exitButton.visibility = if (isDefaultLauncher) View.GONE else View.VISIBLE
        exitButton.text = getString(R.string.exit)
    }

    private fun updateClockWidget() {
        val now = Calendar.getInstance()
        val locale = Locale.getDefault()
        clockTimeText.text = SimpleDateFormat("hh:mm a", locale).format(now.time)
        clockDayText.text = SimpleDateFormat("EEEE", locale).format(now.time)
        clockDateText.text = SimpleDateFormat("MMMM d", locale).format(now.time)
    }

    private fun millisecondsUntilNextMinute(): Long {
        val now = Calendar.getInstance()
        val millisPastMinute =
            now.get(Calendar.SECOND) * 1000L + now.get(Calendar.MILLISECOND)
        return (60_000L - millisPastMinute).coerceAtLeast(1_000L)
    }

    private fun loadHomeApps() {
        val apps = LauncherUtils.queryLaunchableApps(requireContext())
        AppLog.i(
            "HomeFragment: loadHomeApps found %d apps%s",
            apps.size,
            if (apps.isEmpty()) "" else " first=${apps.take(5).joinToString { it.label }}"
        )
        homeAppsAdapter.setApps(apps)
        updateHomeAppsEmptyState()
    }

    private fun toggleAppDrawer() {
        val shouldExpand = appDrawerBehavior.state != BottomSheetBehavior.STATE_EXPANDED
        appDrawerBehavior.state = if (shouldExpand) {
            BottomSheetBehavior.STATE_EXPANDED
        } else {
            BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun updateDrawerUi(isExpanded: Boolean) {
        appDrawerSheet.alpha = if (isExpanded) 1f else 0.82f
        updateDrawerBackground(isExpanded)
        appDrawerChevron.rotation = if (isExpanded) 180f else 0f
        if (isExpanded) {
            appDrawerSearchInput.requestFocus()
        } else {
            appDrawerSearchInput.clearFocus()
        }
    }

    private fun updateDrawerBackground(isExpanded: Boolean) {
        appDrawerSheet.setBackgroundResource(
            if (isExpanded) {
                R.drawable.bg_bottomsheet_opaque
            } else {
                R.drawable.bg_pill_translucent
            }
        )
    }

    private fun updateHomeAppsEmptyState() {
        val isEmpty = homeAppsAdapter.itemCount == 0
        homeAppsRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        homeAppsEmptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun applySystemInsets() {
        val contentStart = appDrawerContent.paddingStart
        val contentTop = appDrawerContent.paddingTop
        val contentEnd = appDrawerContent.paddingEnd
        val contentBottom = appDrawerContent.paddingBottom
        val recyclerBottom = homeAppsRecyclerView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(appDrawerSheet) { _, insets ->
            val navInsets = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            appDrawerContent.setPadding(
                contentStart + navInsets.left,
                contentTop,
                contentEnd + navInsets.right,
                contentBottom + navInsets.bottom
            )
            homeAppsRecyclerView.setPadding(
                homeAppsRecyclerView.paddingLeft,
                homeAppsRecyclerView.paddingTop,
                homeAppsRecyclerView.paddingRight,
                recyclerBottom + navInsets.bottom
            )
            appDrawerBehavior.peekHeight =
                resources.getDimensionPixelSize(R.dimen.app_drawer_peek_height) + navInsets.bottom
            insets
        }
        ViewCompat.requestApplyInsets(appDrawerSheet)
    }

    private data class HomeAppItem(
        val label: String,
        val componentName: android.content.ComponentName,
        val icon: Drawable
    )

    private class HomeAppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.app_icon)
        val label: TextView = itemView.findViewById(R.id.app_label)
    }

    private class HomeAppsAdapter(
        private val onLaunch: (HomeAppItem) -> Unit
    ) : RecyclerView.Adapter<HomeAppViewHolder>() {
        private val allApps = mutableListOf<HomeAppItem>()
        private val visibleApps = mutableListOf<HomeAppItem>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HomeAppViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_app_grid, parent, false)
            return HomeAppViewHolder(view)
        }

        override fun onBindViewHolder(holder: HomeAppViewHolder, position: Int) {
            val app = visibleApps[position]
            holder.icon.setImageDrawable(app.icon)
            holder.label.text = app.label
            holder.itemView.setOnClickListener { onLaunch(app) }
        }

        override fun getItemCount(): Int = visibleApps.size

        fun setApps(items: List<LauncherUtils.LaunchableApp>) {
            allApps.clear()
            allApps.addAll(items.map { HomeAppItem(it.label, it.componentName, it.icon) })
            visibleApps.clear()
            visibleApps.addAll(allApps)
            notifyDataSetChanged()
        }

        fun filter(query: String) {
            val normalizedQuery = query.trim()
            visibleApps.clear()
            if (normalizedQuery.isEmpty()) {
                visibleApps.addAll(allApps)
            } else {
                val lowerQuery = normalizedQuery.lowercase()
                visibleApps.addAll(allApps.filter { app ->
                    app.label.lowercase().contains(lowerQuery) ||
                        app.componentName.packageName.lowercase().contains(lowerQuery)
                })
            }
            notifyDataSetChanged()
        }
    }


    companion object {
        private var hasAutoStarted = false
        var forceSelfModeLaunch = false
        fun resetAutoStart() {
            hasAutoStarted = false
        }
    }
}
