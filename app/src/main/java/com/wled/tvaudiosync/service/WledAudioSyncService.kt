package com.wled.tvaudiosync.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.wled.tvaudiosync.R
import com.wled.tvaudiosync.capture.AudioCaptureSource
import com.wled.tvaudiosync.capture.PlaybackCaptureSource
import com.wled.tvaudiosync.capture.VisualizerCaptureSource
import com.wled.tvaudiosync.data.CaptureSourceMode
import com.wled.tvaudiosync.data.StreamerPrefs
import com.wled.tvaudiosync.data.StreamerSettings
import com.wled.tvaudiosync.dsp.WledFftProcessor
import com.wled.tvaudiosync.network.UdpAudioSender
import com.wled.tvaudiosync.protocol.WledAudioSyncPacket
import com.wled.tvaudiosync.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LiveStreamState(
    val isStreaming: Boolean = false,
    val activeSource: CaptureSourceMode = CaptureSourceMode.VISUALIZER_GLOBAL,
    val targetEndpoint: String = "255.255.255.255:11988",
    val vuDb: Float = -60f,
    val sampleSmth: Float = 0f,
    val samplePeak: Int = 0,
    val majorPeakHz: Float = 0f,
    val fftBins: IntArray = IntArray(16),
    val packetsSent: Long = 0L,
    val lastError: String? = null
)

class WledAudioSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: StreamerPrefs
    private val processor = WledFftProcessor()
    private val packetBuilder = WledAudioSyncPacket()
    private val udpSender = UdpAudioSender()

    private var captureSource: AudioCaptureSource? = null
    private var mediaProjection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var currentSettings = StreamerSettings()
    private var frameCounter = 0
    private var packetsSent = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = StreamerPrefs(this)
        currentSettings = prefs.load()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_SETTINGS -> {
                currentSettings = prefs.load()
                updateNotification()
                return START_STICKY
            }
            ACTION_START -> {
                currentSettings = prefs.load()
                val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
                val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }
                startStreaming(resultCode, projectionData)
                return START_STICKY
            }
        }
        return START_STICKY
    }

    private fun startStreaming(resultCode: Int, projectionData: Intent?) {
        stopStreaming()

        val usePlayback = currentSettings.sourceMode == CaptureSourceMode.PLAYBACK_CAPTURE &&
            resultCode == Activity.RESULT_OK && projectionData != null

        val serviceType = if (usePlayback) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        val notification = buildNotification(usePlayback)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (sec: SecurityException) {
            _state.value = _state.value.copy(
                isStreaming = false,
                lastError = "Chybí oprávnění pro Foreground Service: ${sec.localizedMessage}"
            )
            stopSelf()
            return
        }

        acquireLocks()
        udpSender.open()

        captureSource = if (usePlayback) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(resultCode, projectionData!!)
            mediaProjection = projection
            PlaybackCaptureSource(projection, processor, serviceScope)
        } else {
            VisualizerCaptureSource(processor)
        }

        val activeMode = if (usePlayback) {
            CaptureSourceMode.PLAYBACK_CAPTURE
        } else {
            CaptureSourceMode.VISUALIZER_GLOBAL
        }

        _state.value = _state.value.copy(
            isStreaming = true,
            activeSource = activeMode,
            targetEndpoint = "${currentSettings.targetIp}:${currentSettings.udpPort}",
            lastError = null
        )

        captureSource?.start(
            gainProvider = { currentSettings.gain },
            onFrame = { frame ->
                frameCounter = (frameCounter + 1) and 0xFF
                val payload = packetBuilder.encode(
                    version = currentSettings.protocolVersion,
                    sampleRaw = frame.sampleRaw,
                    sampleSmth = frame.sampleSmth,
                    samplePeak = frame.samplePeak,
                    frameCounter = frameCounter,
                    fftBins = frame.fftBins,
                    zeroCrossingCount = frame.zeroCrossingCount,
                    fftMagnitude = frame.fftMagnitude,
                    fftMajorPeakHz = frame.fftMajorPeakHz
                )

                serviceScope.launch {
                    val sent = udpSender.sendIfDue(
                        payload = payload,
                        host = currentSettings.targetIp,
                        port = currentSettings.udpPort,
                        targetFps = currentSettings.targetFps
                    )
                    if (sent) {
                        packetsSent++
                    }
                    val unsignedBins = IntArray(16) { idx -> frame.fftBins[idx].toInt() and 0xFF }
                    _state.value = LiveStreamState(
                        isStreaming = true,
                        activeSource = activeMode,
                        targetEndpoint = "${currentSettings.targetIp}:${currentSettings.udpPort}",
                        vuDb = frame.vuDb,
                        sampleSmth = frame.sampleSmth,
                        samplePeak = frame.samplePeak,
                        majorPeakHz = frame.fftMajorPeakHz,
                        fftBins = unsignedBins,
                        packetsSent = packetsSent,
                        lastError = null
                    )
                }
            },
            onError = { err ->
                _state.value = _state.value.copy(isStreaming = false, lastError = err)
            }
        )
    }

    private fun stopStreaming() {
        captureSource?.stop()
        captureSource = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        udpSender.close()
        releaseLocks()
        _state.value = _state.value.copy(
            isStreaming = false,
            vuDb = -60f,
            sampleSmth = 0f,
            samplePeak = 0,
            fftBins = IntArray(16)
        )
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WledTvAudioSync::AudioWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(lockMode, "WledTvAudioSync::UdpWifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WLED UDP Audio Sync",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Trvalá služba pro přenos interního zvuku Google TV do WLED (UDP 11988)"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(isPlaybackCapture: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WledAudioSyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val modeLabel = if (isPlaybackCapture) "Playback Capture" else "Visualizer (Session 0)"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_audio)
            .setContentTitle("WLED Audio Sync aktivní")
            .setContentText("$modeLabel -> ${currentSettings.targetIp}:${currentSettings.udpPort}")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Zastavit", stopIntent)
            .build()
    }

    private fun updateNotification() {
        if (!_state.value.isStreaming) return
        val nm = getSystemService(NotificationManager::class.java)
        val isPlayback = _state.value.activeSource == CaptureSourceMode.PLAYBACK_CAPTURE
        nm.notify(NOTIFICATION_ID, buildNotification(isPlayback))
    }

    override fun onDestroy() {
        stopStreaming()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.wled.tvaudiosync.action.START"
        const val ACTION_STOP = "com.wled.tvaudiosync.action.STOP"
        const val ACTION_RELOAD_SETTINGS = "com.wled.tvaudiosync.action.RELOAD"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "wled_audio_sync_fg"
        private const val NOTIFICATION_ID = 11988

        private val _state = MutableStateFlow(LiveStreamState())
        val state: StateFlow<LiveStreamState> = _state.asStateFlow()
    }
}
