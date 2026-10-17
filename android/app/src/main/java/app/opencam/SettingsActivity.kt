package app.opencam

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import app.opencam.core.CamCaps
import app.opencam.databinding.ActivitySettingsBinding

/** Every setting is local to the phone; nothing is synced or uploaded. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var ui: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(ui.root)
        prefs = Prefs(this)

        ui.editPort.setText(prefs.port.toString())

        val resolutions = CamCaps.PRESETS.map { it.name }
        ui.spinnerResolution.adapter = simpleAdapter(resolutions)
        ui.spinnerResolution.setSelection(resolutions.indexOf(prefs.resolution).coerceAtLeast(0))

        val fpsOptions = listOf("15", "24", "30", "60")
        ui.spinnerFps.adapter = simpleAdapter(fpsOptions)
        ui.spinnerFps.setSelection(fpsOptions.indexOf(prefs.fps.toString()).coerceAtLeast(0))

        val cameras = listOf(getString(R.string.pref_camera_back), getString(R.string.pref_camera_front))
        ui.spinnerCamera.adapter = simpleAdapter(cameras)
        ui.spinnerCamera.setSelection(if (prefs.cameraFacing == 1) 1 else 0)

        ui.switchAudio.isChecked = prefs.audioEnabled
        ui.switchMirror.isChecked = prefs.mirrorFront
        ui.switchKeepAwake.isChecked = prefs.keepAwake
        ui.switchDiscovery.isChecked = prefs.discovery

        ui.editPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitPort()
        }

        ui.spinnerResolution.onItemSelected { position ->
            prefs.resolution = resolutions[position]
            pushToService()
        }
        ui.spinnerFps.onItemSelected { position ->
            prefs.fps = fpsOptions[position].toInt()
            pushToService()
        }
        ui.spinnerCamera.onItemSelected { position ->
            prefs.cameraFacing = if (position == 1) 1 else 0
            pushToService()
        }

        ui.switchAudio.setOnCheckedChangeListener { _, checked ->
            prefs.audioEnabled = checked
            pushToService()
        }
        ui.switchMirror.setOnCheckedChangeListener { _, checked ->
            prefs.mirrorFront = checked
            pushToService()
        }
        ui.switchKeepAwake.setOnCheckedChangeListener { _, checked ->
            prefs.keepAwake = checked
            if (checked) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            pushToService()
        }
        ui.switchDiscovery.setOnCheckedChangeListener { _, checked ->
            prefs.discovery = checked
            pushToService()
        }

        ui.btnRestoreDefaults.setOnClickListener {
            prefs.restoreDefaults()
            ui.editPort.setText(prefs.port.toString())
            ui.spinnerResolution.setSelection(resolutions.indexOf(prefs.resolution).coerceAtLeast(0))
            ui.spinnerFps.setSelection(fpsOptions.indexOf(prefs.fps.toString()).coerceAtLeast(0))
            ui.spinnerCamera.setSelection(0)
            ui.switchAudio.isChecked = prefs.audioEnabled
            ui.switchMirror.isChecked = prefs.mirrorFront
            ui.switchKeepAwake.isChecked = prefs.keepAwake
            ui.switchDiscovery.isChecked = prefs.discovery
            pushToService()
        }
    }

    private fun commitPort() {
        val parsed = ui.editPort.text.toString().toIntOrNull()
        if (parsed == null || parsed !in 1024..65000) {
            ui.editPort.setText(prefs.port.toString())
            return
        }
        prefs.port = parsed
        pushToService()
    }

    private fun pushToService() {
        // ACTION_START on an already-running service re-reads Prefs and applies
        // whatever changed, without dropping connected clients unnecessarily.
        startService(Intent(this, CamService::class.java).setAction(CamService.ACTION_START))
    }

    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

}
