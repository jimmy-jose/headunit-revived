package org.xs.headunitlauncher.connection

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.xs.headunitlauncher.utils.AppLog

class WifiDirectDeviceNameResolver(private val context: Context) {

    fun resolve(onResolved: (String?) -> Unit) {
        val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            onResolved(null)
            return
        }

        val channel = manager.initialize(context, Looper.getMainLooper(), null)
        if (channel == null) {
            onResolved(null)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var delivered = false
            val mainHandler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (!delivered) {
                    delivered = true
                    onResolved(null)
                }
            }
            mainHandler.postDelayed(timeoutRunnable, 4000L)

            try {
                manager.requestDeviceInfo(channel) { device ->
                    if (delivered) return@requestDeviceInfo
                    delivered = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    onResolved(device?.deviceName?.takeIf { it.isNotBlank() })
                }
            } catch (e: Exception) {
                AppLog.w("WifiDirectDeviceNameResolver: requestDeviceInfo failed: ${e.message}")
                if (!delivered) {
                    delivered = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    onResolved(null)
                }
            }
        } else {
            onResolved(null)
        }
    }
}
