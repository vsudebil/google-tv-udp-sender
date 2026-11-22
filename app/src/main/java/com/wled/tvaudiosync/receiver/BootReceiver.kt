package com.wled.tvaudiosync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.wled.tvaudiosync.data.CaptureSourceMode
import com.wled.tvaudiosync.data.StreamerPrefs
import com.wled.tvaudiosync.service.WledAudioSyncService

/**
 * Automaticky spustí streamování zvuku do WLED po zapnutí / restartu Google TV,
 * pokud je zapnutý Autostart.
 *
 * Poznámka: Po startu systému lze bez uživatelského dialogu spustit pouze Visualizer(0),
 * protože MediaProjection vyžaduje interaktivní potvrzení.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val prefs = StreamerPrefs(context)
        val settings = prefs.load()
        if (!settings.autostartOnBoot) return

        if (settings.sourceMode == CaptureSourceMode.PLAYBACK_CAPTURE) {
            prefs.save(settings.copy(sourceMode = CaptureSourceMode.VISUALIZER_GLOBAL))
        }

        val serviceIntent = Intent(context, WledAudioSyncService::class.java).apply {
            this.action = WledAudioSyncService.ACTION_START
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
