package com.wled.tvaudiosync.data

import android.content.Context

enum class CaptureSourceMode {
    VISUALIZER_GLOBAL,
    PLAYBACK_CAPTURE
}

enum class WledProtocolVersion {
    V2_00002,
    V1_00001
}

data class StreamerSettings(
    val sourceMode: CaptureSourceMode = CaptureSourceMode.VISUALIZER_GLOBAL,
    val protocolVersion: WledProtocolVersion = WledProtocolVersion.V2_00002,
    val targetIp: String = "255.255.255.255",
    val udpPort: Int = 11988,
    val gain: Float = 1.30f,
    val targetFps: Int = 45,
    val autostartOnBoot: Boolean = true
)

class StreamerPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "wled_tv_audio_sync_prefs",
        Context.MODE_PRIVATE
    )

    fun load(): StreamerSettings {
        val default = StreamerSettings()
        val sourceName = prefs.getString(KEY_SOURCE_MODE, default.sourceMode.name)
        val protoName = prefs.getString(KEY_PROTOCOL, default.protocolVersion.name)
        return StreamerSettings(
            sourceMode = runCatching { CaptureSourceMode.valueOf(sourceName ?: "") }
                .getOrDefault(default.sourceMode),
            protocolVersion = runCatching { WledProtocolVersion.valueOf(protoName ?: "") }
                .getOrDefault(default.protocolVersion),
            targetIp = prefs.getString(KEY_TARGET_IP, default.targetIp)
                ?.ifBlank { default.targetIp } ?: default.targetIp,
            udpPort = prefs.getInt(KEY_UDP_PORT, default.udpPort).coerceIn(1, 65535),
            gain = prefs.getFloat(KEY_GAIN, default.gain).coerceIn(0.2f, 4.0f),
            targetFps = prefs.getInt(KEY_FPS, default.targetFps).coerceIn(20, 60),
            autostartOnBoot = prefs.getBoolean(KEY_AUTOSTART, default.autostartOnBoot)
        )
    }

    fun save(settings: StreamerSettings) {
        prefs.edit()
            .putString(KEY_SOURCE_MODE, settings.sourceMode.name)
            .putString(KEY_PROTOCOL, settings.protocolVersion.name)
            .putString(KEY_TARGET_IP, settings.targetIp.trim())
            .putInt(KEY_UDP_PORT, settings.udpPort.coerceIn(1, 65535))
            .putFloat(KEY_GAIN, settings.gain.coerceIn(0.2f, 4.0f))
            .putInt(KEY_FPS, settings.targetFps.coerceIn(20, 60))
            .putBoolean(KEY_AUTOSTART, settings.autostartOnBoot)
            .apply()
    }

    companion object {
        private const val KEY_SOURCE_MODE = "source_mode"
        private const val KEY_PROTOCOL = "protocol_version"
        private const val KEY_TARGET_IP = "target_ip"
        private const val KEY_UDP_PORT = "udp_port"
        private const val KEY_GAIN = "gain"
        private const val KEY_FPS = "target_fps"
        private const val KEY_AUTOSTART = "autostart_on_boot"
    }
}
