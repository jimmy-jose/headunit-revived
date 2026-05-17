package org.xs.hulhelper.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

class WifiDirectNameDiscoveryManager(private val context: Context) {

    interface Callback {
        fun onSearching()
        fun onDiscovered(name: String)
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
    private var isRunning = false
    private var activeEndpointId: String? = null

    private val timeoutRunnable = Runnable {
        if (isRunning) {
            callback?.onError("Timed out waiting for the headunit broadcast.")
            stop()
        }
    }

    fun hasRequiredPermissions(): Boolean {
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasCoarse && !hasFine) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val hasConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!hasScan || !hasConnect) return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNearby = ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            if (!hasNearby) return false
        }

        return true
    }

    fun start(callback: Callback) {
        stop()
        this.callback = callback
        isRunning = true
        callback.onSearching()
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()

        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnFailureListener { e ->
                this.callback?.onError("Failed to search for the headunit broadcast: ${e.message}")
                stop()
            }
    }

    fun stop() {
        mainHandler.removeCallbacks(timeoutRunnable)
        if (isRunning) {
            connectionsClient.stopDiscovery()
            activeEndpointId?.let {
                connectionsClient.disconnectFromEndpoint(it)
            }
            connectionsClient.stopAllEndpoints()
        }
        activeEndpointId = null
        isRunning = false
        callback?.onStopped()
        callback = null
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!isRunning || activeEndpointId != null) return
            activeEndpointId = endpointId
            connectionsClient.stopDiscovery()
            connectionsClient.requestConnection(Build.MODEL.ifBlank { "HeadUnitHelper" }, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e ->
                    callback?.onError("Failed to connect to headunit broadcast: ${e.message}")
                    stop()
                }
        }

        override fun onEndpointLost(endpointId: String) {
            if (activeEndpointId == endpointId && isRunning) {
                callback?.onError("Headunit broadcast was lost before the Wi-Fi Direct name was received.")
                stop()
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                callback?.onError("Headunit connection failed: ${result.status.statusMessage ?: result.status.statusCode}")
                stop()
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (isRunning) {
                callback?.onError("Headunit disconnected before the Wi-Fi Direct name was received.")
                stop()
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val message = String(bytes)
            if (message.startsWith(PAYLOAD_PREFIX)) {
                val name = message.removePrefix(PAYLOAD_PREFIX).trim()
                if (name.isNotEmpty()) {
                    callback?.onDiscovered(name)
                    stop()
                } else {
                    callback?.onError("Received an empty Wi-Fi Direct name from the headunit.")
                    stop()
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }
}
