package org.xs.headunitlauncher.utils

import android.content.Context
import android.media.MediaPlayer
import org.xs.headunitlauncher.R

/**
 * Plays a silent audio loop to keep the media focus on the app.
 * This is a common trick used in Chinese headunits to ensure steering wheel 
 * buttons are always routed to the active foreground app.
 */
class SilentAudioPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null

    fun start() {
        if (mediaPlayer != null) return
        
        try {
            // Note: R.raw.mute should be a very short silent wav/mp3 file
            mediaPlayer = MediaPlayer.create(context, R.raw.mute)
            mediaPlayer?.apply {
                isLooping = true
                setVolume(0f, 0f)
                start()
            }
            AppLog.i("SilentAudioPlayer: Started silent loop for media focus.")
        } catch (e: Exception) {
            AppLog.e("SilentAudioPlayer: Failed to start silent loop. Is R.raw.mute missing?", e)
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {}
        mediaPlayer = null
        AppLog.i("SilentAudioPlayer: Stopped silent loop.")
    }
}
