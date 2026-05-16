package org.xs.headunitlauncher.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.xs.headunitlauncher.utils.AppLog

class WifiDirectNameBroadcastManager(private val context: Context) {

    interface Callback {
        fun onWaiting()
        fun onSent(name: String)
        fun onError(message: String)
        fun onStopped()
    }

    companion object {
        private const val SERVICE_ID = "org.xs.hulhelper.wifidirectname"
        private const val PAYLOAD_PREFIX = "WIFI_DIRECT_NAME:"
        private const val TIMEOUT_MS = 60_000L
    }

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: Callback? = null
    private var pendingName: String? = null
    private var isRunning = false
    private var activeEndpointId: String? = null

    private val timeoutRunnable = Runnable {
        if (isRunning) {
            callback?.onError("Timed out waiting for HeadUnit Helper.")
            stop()
        }
    }

    fun hasRequiredPermissions(): Boolean {
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasCoarse && !hasFine) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasAdvertise = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val hasConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!hasAdvertise || !hasConnect) return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNearby = ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            if (!hasNearby) return false
        }

        return true
    }

    fun start(name: String, callback: Callback) {
        stop()
        this.callback = callback
        pendingName = name
        isRunning = true

        val endpointName = Build.MODEL.ifBlank { "HeadUnitLauncher" }
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()

        callback.onWaiting()
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)

        AppLog.i("WifiDirectNameBroadcastManager: Advertising Wi-Fi Direct name '$name'")
        connectionsClient.startAdvertising(
            endpointName,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnFailureListener { e ->
            this.callback?.onError("Failed to broadcast Wi-Fi Direct name: ${e.message}")
            stop()
        }
    }

    fun stop() {
        mainHandler.removeCallbacks(timeoutRunnable)
        if (isRunning) {
            connectionsClient.stopAdvertising()
            activeEndpointId?.let {
                connectionsClient.disconnectFromEndpoint(it)
            }
            connectionsClient.stopAllEndpoints()
        }
        activeEndpointId = null
        pendingName = null
        isRunning = false
        callback?.onStopped()
        callback = null
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            AppLog.i("WifiDirectNameBroadcastManager: Connection initiated by ${info.endpointName} ($endpointId)")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    activeEndpointId = endpointId
                    val name = pendingName ?: run {
                        callback?.onError("Wi-Fi Direct name is no longer available.")
                        stop()
                        return
                    }
                    val payload = Payload.fromBytes((PAYLOAD_PREFIX + name).toByteArray())
                    connectionsClient.sendPayload(endpointId, payload)
                        .addOnSuccessListener {
                            callback?.onSent(name)
                            stop()
                        }
                        .addOnFailureListener { e ->
                            callback?.onError("Failed to send Wi-Fi Direct name: ${e.message}")
                            stop()
                        }
                }
                else -> {
                    callback?.onError("Helper connection failed: ${result.status.statusMessage ?: result.status.statusCode}")
                    stop()
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (isRunning && activeEndpointId == endpointId) {
                callback?.onError("Helper disconnected before the Wi-Fi Direct name was sent.")
                stop()
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) = Unit
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }
}
