package org.xs.headunitlauncher.app

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.xs.headunitlauncher.aap.AapService
import org.xs.headunitlauncher.billing.BillingAccessEnforcer
import org.xs.headunitlauncher.connection.CommManager
import org.xs.headunitlauncher.main.MainActivity
import org.xs.headunitlauncher.utils.AppLog
import org.xs.headunitlauncher.utils.Settings
import android.os.UserManager
import android.os.Build
import android.os.SystemClock

class AutoStartReceiver : BroadcastReceiver() {

    companion object {
        private const val AUTO_START_COOLDOWN_MS = 30_000L
        private var lastMatchedMac: String? = null
        private var lastMatchElapsedRealtime: Long = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        // Use device-protected storage so the BT MAC is readable during locked boot
        val targetMac = Settings.getAutoStartBtMac(context)

        if (targetMac.isEmpty()) return
        
        val isLocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && 
                      !(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
        
        val app = org.xs.headunitlauncher.App.provide(context)
        val settings = app.settings
        val connectionState = app.commManager.connectionState.value

        // Nearby Helper mode is manually initiated from the home screen. Starting the
        // service again from a BT event adds churn right in the middle of the Nearby flow.
        if (!isLocked && settings.wifiConnectionMode == 2 && settings.helperConnectionStrategy == 2) {
            AppLog.i("AutoStartReceiver: Ignoring BT auto-start while Wireless Helper Nearby mode is active.")
            return
        }

        // Don't trigger auto-start if we already have an active or in-progress AA session.
        if (!isLocked && connectionState !is CommManager.ConnectionState.Disconnected) {
            AppLog.d("AutoStartReceiver: Android Auto is already active/in progress ($connectionState). Ignoring BT event.")
            return
        }

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            AppLog.i("BT Device connected: ${device?.name} (${device?.address})")

            if (device?.address == targetMac) {
                val now = SystemClock.elapsedRealtime()
                if (lastMatchedMac == targetMac && now - lastMatchElapsedRealtime < AUTO_START_COOLDOWN_MS) {
                    AppLog.i("AutoStartReceiver: Skipping duplicate BT auto-start for $targetMac; cooldown active (${AUTO_START_COOLDOWN_MS - (now - lastMatchElapsedRealtime)}ms remaining).")
                    return
                }
                lastMatchedMac = targetMac
                lastMatchElapsedRealtime = now

                if (!BillingAccessEnforcer.ensureAccessOrLaunchGate(
                        context,
                        "Bluetooth auto-start",
                        Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Bluetooth auto-start")
                        }
                    )) {
                    AppLog.i("AutoStartReceiver: blocked by billing gate")
                    return
                }

                AppLog.i("MATCH! Starting AapService via Bluetooth Auto-start...")
                
                // Start the service to make the app alive
                val serviceIntent = Intent(context, AapService::class.java)
                try {
                    androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    AppLog.e("Failed to start AapService from background: ${e.message}")
                }

                // Also attempt to start the UI (might be blocked on Android 10+ without special permission)
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Bluetooth auto-start")
                }
                try {
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    AppLog.w("Could not start UI from background (expected on Android 10+): ${e.message}")
                }
            }
        }
    }
}
