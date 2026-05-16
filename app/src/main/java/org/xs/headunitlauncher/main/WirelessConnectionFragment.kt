package org.xs.headunitlauncher.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.xs.headunitlauncher.App
import org.xs.headunitlauncher.R
import org.xs.headunitlauncher.aap.AapService
import org.xs.headunitlauncher.main.settings.SettingItem
import org.xs.headunitlauncher.main.settings.SettingsAdapter
import org.xs.headunitlauncher.utils.Settings
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.xs.headunitlauncher.connection.NativeAaHandshakeManager
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class WirelessConnectionFragment : Fragment(R.layout.fragment_wireless_connection) {

    private data class WirelessModeOption(
        val mode: Int,
        val labelResId: Int,
    )

    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar

    private var pendingWifiConnectionMode: Int? = null
    private var pendingAutoEnableHotspot: Boolean? = null
    private var pendingWaitForWifi: Boolean? = null
    private var pendingWaitForWifiTimeout: Int? = null
    
    private var hasChanges = false
    private val SAVE_ITEM_ID = 1001
    private val visibleWirelessModes = listOf(
        WirelessModeOption(mode = 3, labelResId = R.string.wireless_mode_native_full),
        WirelessModeOption(mode = 2, labelResId = R.string.wireless_mode_helper_full),
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted, proceed with Native AA selection (which == 3)
            handleNativeAaSelection()
        } else {
            Toast.makeText(requireContext(), R.string.bt_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = App.provide(requireContext()).settings

        pendingWifiConnectionMode = normalizeVisibleMode(settings.wifiConnectionMode)
        pendingAutoEnableHotspot = settings.autoEnableHotspot
        pendingWaitForWifi = settings.waitForWifiBeforeWifiDirect
        pendingWaitForWifiTimeout = settings.waitForWifiTimeout

        toolbar = view.findViewById(R.id.toolbar)
        recyclerView = view.findViewById(R.id.settingsRecyclerView)
        adapter = SettingsAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        setupToolbar()
        updateSettingsList()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { handleBack() }
        updateSaveButtonState()
    }

    private fun updateSaveButtonState() {
        toolbar.menu.clear()
        if (hasChanges) {
            val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 0, getString(R.string.save))
            saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            
            toolbar.setOnMenuItemClickListener { item ->
                if (item.itemId == SAVE_ITEM_ID) {
                    saveSettings()
                    true
                } else false
            }
        }
    }

    private fun updateSettingsList() {
        val items = mutableListOf<SettingItem>()
        val nativeWirelessUnsupported = settings.nativeWirelessUnsupported
        val selectedMode = normalizeVisibleMode(pendingWifiConnectionMode)
        val selectedModeLabel = visibleWirelessModes
            .firstOrNull { it.mode == selectedMode }
            ?.let { getString(it.labelResId) }
            .orEmpty()

        // Add 2.4GHz Warning Banner at the top
        items.add(SettingItem.InfoBanner(
            stableId = "wireless24ghzWarning",
            textResId = R.string.wireless_24ghz_warning
        ))

        if (nativeWirelessUnsupported) {
            items.add(SettingItem.InfoBanner(
                stableId = "nativeWirelessUnsupportedWarning",
                textResId = R.string.native_wireless_unsupported_warning
            ))

            if (selectedMode != 2) {
                items.add(SettingItem.ActionButton(
                    stableId = "useWirelessHelperButton",
                    textResId = R.string.use_wireless_helper,
                    onClick = {
                        pendingWifiConnectionMode = 2
                        checkChanges()
                        updateSettingsList()
                    }
                ))
            }
        }

        items.add(SettingItem.CategoryHeader("wireless_mode", R.string.wireless_mode))
        
        items.add(SettingItem.SettingEntry(
            stableId = "wifiConnectionMode",
            nameResId = R.string.wireless_mode,
            value = selectedModeLabel,
            onClick = { _ ->
                val wifiModes = visibleWirelessModes.map { getString(it.labelResId) }.toTypedArray()
                val currentSelection = visibleWirelessModes.indexOfFirst { it.mode == selectedMode }.coerceAtLeast(0)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.wireless_mode)
                    .setSingleChoiceItems(wifiModes, currentSelection) { dialog, which ->
                        dialog.dismiss()

                        val selectedOption = visibleWirelessModes[which]
                        if (selectedOption.mode == 3) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                                ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                requestPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                            } else {
                                handleNativeAaSelection()
                            }
                        } else {
                            pendingWifiConnectionMode = selectedOption.mode
                            checkChanges()
                            updateSettingsList()
                        }
                    }
                    .show()
            }
        ))

        if (selectedMode == 2) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "autoEnableHotspot",
                nameResId = R.string.auto_enable_hotspot,
                descriptionResId = R.string.auto_enable_hotspot_description,
                isChecked = pendingAutoEnableHotspot ?: false,
                onCheckedChanged = { isChecked ->
                    if (isChecked) {
                        if (Build.VERSION.SDK_INT >= 23 && !SystemSettings.System.canWrite(requireContext())) {
                            showPermissionDialog()
                        } else {
                            showExperimentalWarning()
                        }
                    } else {
                        pendingAutoEnableHotspot = false
                        checkChanges()
                        updateSettingsList()
                    }
                }
            ))
        }

        if (selectedMode == 2) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "waitForWifi",
                nameResId = R.string.wait_for_wifi,
                descriptionResId = R.string.wait_for_wifi_description,
                isChecked = pendingWaitForWifi ?: false,
                onCheckedChanged = { isChecked ->
                    pendingWaitForWifi = isChecked
                    checkChanges()
                    updateSettingsList()
                }
            ))

            if (pendingWaitForWifi == true) {
                items.add(SettingItem.SliderSettingEntry(
                    stableId = "waitForWifiTimeout",
                    nameResId = R.string.wait_for_wifi_timeout,
                    value = "${pendingWaitForWifiTimeout}s",
                    sliderValue = (pendingWaitForWifiTimeout ?: 10).toFloat(),
                    valueFrom = 5f,
                    valueTo = 30f,
                    stepSize = 1f,
                    onValueChanged = { value ->
                        pendingWaitForWifiTimeout = value.toInt()
                        checkChanges()
                        updateSettingsList()
                    }
                ))
            }
        }

        // Add bottom save button
        if (hasChanges) {
            items.add(SettingItem.ActionButton(
                stableId = "bottomSaveButton",
                textResId = R.string.save,
                onClick = { saveSettings() }
            ))
        }

        adapter.submitList(items)
    }

    private fun showPermissionDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.hotspot_permission_title)
            .setMessage(R.string.hotspot_permission_message)
            .setPositiveButton(R.string.open_settings) { dialog, _ ->
                val intent = Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                startActivity(intent)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                pendingAutoEnableHotspot = false
                checkChanges()
                updateSettingsList()
            }
            .show()
    }

    private fun handleNativeAaSelection() {
        if (settings.nativeWirelessUnsupported) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.not_supported_nativeaa)
                .setMessage(R.string.native_wireless_unsupported_warning)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    pendingWifiConnectionMode = 2
                    checkChanges()
                    updateSettingsList()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        if (NativeAaHandshakeManager.checkCompatibility(requireContext())) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.supported_nativeaa)
                .setMessage(R.string.supported_nativeaa_desc)
                .setPositiveButton(android.R.string.ok) { dialog2, _ ->
                    pendingWifiConnectionMode = 3
                    checkChanges()
                    updateSettingsList()
                    dialog2.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.not_supported_nativeaa)
                .setMessage(R.string.not_supported_nativeaa_desc)
                .setPositiveButton(android.R.string.ok) { dialog2, _ ->
                    pendingWifiConnectionMode = 3
                    checkChanges()
                    updateSettingsList()
                    dialog2.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showExperimentalWarning() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.hotspot_warning_title)
            .setMessage(R.string.hotspot_warning_message)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                pendingAutoEnableHotspot = true
                checkChanges()
                updateSettingsList()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingAutoEnableHotspot = false
                checkChanges()
                updateSettingsList()
            }
            .show()
    }

    private fun checkChanges() {
        val anyChange = normalizeVisibleMode(pendingWifiConnectionMode) != settings.wifiConnectionMode ||
                        pendingAutoEnableHotspot != settings.autoEnableHotspot ||
                        pendingWaitForWifi != settings.waitForWifiBeforeWifiDirect ||
                        pendingWaitForWifiTimeout != settings.waitForWifiTimeout
        
        if (hasChanges != anyChange) {
            hasChanges = anyChange
            updateSaveButtonState()
            updateSettingsList()
        }
    }

    private fun saveSettings() {
        val oldMode = settings.wifiConnectionMode
        settings.wifiConnectionMode = normalizeVisibleMode(pendingWifiConnectionMode)
        settings.autoEnableHotspot = pendingAutoEnableHotspot!!
        settings.waitForWifiBeforeWifiDirect = pendingWaitForWifi!!
        settings.waitForWifiTimeout = pendingWaitForWifiTimeout!!

        settings.commit()

        if (oldMode != settings.wifiConnectionMode) {
            val intent = Intent(requireContext(), AapService::class.java).apply {
                val mode = settings.wifiConnectionMode
                action = if (mode == 2 || mode == 3)
                    AapService.ACTION_START_WIRELESS else AapService.ACTION_STOP_WIRELESS
            }
            requireContext().startService(intent)
        }

        hasChanges = false
        updateSaveButtonState()
        updateSettingsList()
        Toast.makeText(context, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private fun handleBack() {
        if (hasChanges) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.unsaved_changes)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.discard) { _, _ -> findNavController().popBackStack() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            findNavController().popBackStack()
        }
    }

    private fun normalizeVisibleMode(mode: Int?): Int = if (mode == 3) 3 else 2
}
