package com.wled.tvaudiosync.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wled.tvaudiosync.data.CaptureSourceMode
import com.wled.tvaudiosync.data.StreamerPrefs
import com.wled.tvaudiosync.data.StreamerSettings
import com.wled.tvaudiosync.data.WledProtocolVersion
import com.wled.tvaudiosync.databinding.ActivityMainBinding
import com.wled.tvaudiosync.service.WledAudioSyncService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: StreamerPrefs
    private var settings = StreamerSettings()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            beginStreamFlow()
        } else {
            Toast.makeText(
                this,
                "Oprávnění RECORD_AUDIO je vyžadováno pro zachycení interního zvuku.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            launchService(result.resultCode, result.data)
        } else {
            Toast.makeText(this, "Playback Capture nebyl potvrzen.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = StreamerPrefs(this)
        settings = prefs.load()
        renderSettings()
        bindControls()
        observeService()

        binding.btnToggleStream.requestFocus()
    }

    private fun bindControls() {
        binding.btnToggleStream.setOnClickListener {
            if (WledAudioSyncService.state.value.isStreaming) {
                startService(
                    Intent(this, WledAudioSyncService::class.java)
                        .setAction(WledAudioSyncService.ACTION_STOP)
                )
            } else {
                persistInputsFromFields()
                ensurePermissionsAndStart()
            }
        }

        binding.btnSourceVisualizer.setOnClickListener {
            switchSourceMode(CaptureSourceMode.VISUALIZER_GLOBAL)
        }

        binding.btnSourcePlayback.setOnClickListener {
            switchSourceMode(CaptureSourceMode.PLAYBACK_CAPTURE)
        }

        binding.btnProtocolToggle.setOnClickListener {
            val next = if (settings.protocolVersion == WledProtocolVersion.V2_00002) {
                WledProtocolVersion.V1_00001
            } else {
                WledProtocolVersion.V2_00002
            }
            updateSettings(settings.copy(protocolVersion = next))
        }

        binding.btnAutostartToggle.setOnClickListener {
            updateSettings(settings.copy(autostartOnBoot = !settings.autostartOnBoot))
        }

        binding.editTargetIp.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                persistInputsFromFields()
                true
            } else false
        }

        binding.editUdpPort.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                persistInputsFromFields()
                true
            } else false
        }

        binding.btnSaveNetwork.setOnClickListener {
            persistInputsFromFields()
            Toast.makeText(
                this,
                "Cíl uložen: ${settings.targetIp}:${settings.udpPort}",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Ovládání Gain šipkami vlevo/vpravo přímo na ovladači Google TV
        binding.gainControlRow.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val next = ((settings.gain - 0.1f) * 10).roundToInt() / 10f
                        updateSettings(settings.copy(gain = next.coerceIn(0.2f, 4.0f)))
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val next = ((settings.gain + 0.1f) * 10).roundToInt() / 10f
                        updateSettings(settings.copy(gain = next.coerceIn(0.2f, 4.0f)))
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    private fun switchSourceMode(mode: CaptureSourceMode) {
        if (settings.sourceMode == mode) return
        val wasStreaming = WledAudioSyncService.state.value.isStreaming
        updateSettings(settings.copy(sourceMode = mode))
        if (wasStreaming) {
            startService(
                Intent(this, WledAudioSyncService::class.java)
                    .setAction(WledAudioSyncService.ACTION_STOP)
            )
            ensurePermissionsAndStart()
        }
    }

    private fun persistInputsFromFields() {
        val ip = binding.editTargetIp.text?.toString()?.trim().orEmpty().ifEmpty { "255.255.255.255" }
        val port = binding.editUdpPort.text?.toString()?.toIntOrNull()?.coerceIn(1, 65535) ?: 11988
        updateSettings(settings.copy(targetIp = ip, udpPort = port))
    }

    private fun updateSettings(newSettings: StreamerSettings) {
        settings = newSettings
        prefs.save(newSettings)
        renderSettings()
        if (WledAudioSyncService.state.value.isStreaming) {
            startService(
                Intent(this, WledAudioSyncService::class.java)
                    .setAction(WledAudioSyncService.ACTION_RELOAD_SETTINGS)
            )
        }
    }

    private fun renderSettings() {
        if (binding.editTargetIp.text.toString() != settings.targetIp) {
            binding.editTargetIp.setText(settings.targetIp)
        }
        if (binding.editUdpPort.text.toString() != settings.udpPort.toString()) {
            binding.editUdpPort.setText(settings.udpPort.toString())
        }
        binding.txtGainValue.text = String.format("%.1fx", settings.gain)
        binding.gainProgress.progress = ((settings.gain - 0.2f) / 3.8f * 100).roundToInt().coerceIn(0, 100)

        binding.btnSourceVisualizer.isSelected = settings.sourceMode == CaptureSourceMode.VISUALIZER_GLOBAL
        binding.btnSourcePlayback.isSelected = settings.sourceMode == CaptureSourceMode.PLAYBACK_CAPTURE
        binding.btnProtocolToggle.text = when (settings.protocolVersion) {
            WledProtocolVersion.V2_00002 -> "Protokol: WLED v2 (00002 · 44 B)"
            WledProtocolVersion.V1_00001 -> "Protokol: WLED v1 (00001 · 83 B)"
        }
        binding.btnAutostartToggle.text = if (settings.autostartOnBoot) {
            "Autostart po zapnutí TV: Zapnuto"
        } else {
            "Autostart po zapnutí TV: Vypnuto"
        }
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            beginStreamFlow()
        }
    }

    private fun beginStreamFlow() {
        if (settings.sourceMode == CaptureSourceMode.PLAYBACK_CAPTURE) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        } else {
            launchService(Activity.RESULT_CANCELED, null)
        }
    }

    private fun launchService(resultCode: Int, data: Intent?) {
        val intent = Intent(this, WledAudioSyncService::class.java).apply {
            action = WledAudioSyncService.ACTION_START
            putExtra(WledAudioSyncService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
            if (data != null) {
                putExtra(WledAudioSyncService.EXTRA_PROJECTION_DATA, data)
            }
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun observeService() {
        lifecycleScope.launch {
            WledAudioSyncService.state.collectLatest { st ->
                binding.btnToggleStream.text = if (st.isStreaming) {
                    "Zastavit streamování"
                } else {
                    "Spustit streamování do WLED"
                }
                binding.txtStatus.text = if (st.isStreaming) {
                    "Běží na pozadí · ${st.targetEndpoint} · Odesláno paketů: ${st.packetsSent}"
                } else {
                    st.lastError ?: "Připraveno k odesílání WLED UDP Audio Sync"
                }
                binding.spectrumMeter.updateFrame(st.fftBins, st.vuDb, st.samplePeak > 0)
            }
        }
    }
}
