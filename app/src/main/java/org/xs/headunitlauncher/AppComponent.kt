package org.xs.headunitlauncher

import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import org.xs.headunitlauncher.connection.CommManager
import org.xs.headunitlauncher.decoder.AudioDecoder
import org.xs.headunitlauncher.decoder.VideoDecoder
import org.xs.headunitlauncher.utils.Settings

class AppComponent(private val app: App) {

    val settings = Settings(app)
    val videoDecoder = VideoDecoder(settings)
    val audioDecoder = AudioDecoder()

    val notificationManager: NotificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val wifiManager: WifiManager
        get() = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val commManager = CommManager(app, settings, audioDecoder, videoDecoder)
}
