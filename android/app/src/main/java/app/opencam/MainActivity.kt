package app.opencam

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.opencam.core.CamCaps
import app.opencam.core.CameraSettings
import app.opencam.databinding.ActivityMainBinding

/**
 * The streaming screen. Owns the preview surface and exposes every manual camera
 * control the phone supports. All controls act on the running service, so a
 * desktop client sees the same state.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var service: CamService? = null
    private var bound = false

    /** Which parameter the bottom slider is currently editing. */
    private enum class Panel { NONE, ZOOM, EV, ISO, SS, MF, WB }

    private var panel = Panel.NONE

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUi()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? CamService.LocalBinder ?: return
            service = b.service
            bound = true
            val holder = ui.cameraSurface.holder
            if (holder.surface.isValid) service?.attachPreview(holder)
            refreshUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            service?.attachPreview(holder)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            service?.detachPreview()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        prefs = Prefs(this)

        ui.cameraSurface.holder.addCallback(surfaceCallback)

        wireControls()

        if (!hasPermissions()) {
            ui.btnPermError.visibility = View.VISIBLE
            requestPermissions()
        } else {
            startAndBind()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(CamService.BROADCAST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
        applyScreenPreference()
        if (hasPermissions() && !bound) startAndBind()
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        if (bound) {
            service?.detachPreview()
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ plumbing

    private fun applyScreenPreference() {
        if (prefs.keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun startAndBind() {
        val intent = Intent(this, CamService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun hasPermissions(): Boolean {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (hasPermissions()) {
            ui.btnPermError.visibility = View.GONE
            startAndBind()
        } else {
            ui.btnPermError.visibility = View.VISIBLE
            Toast.makeText(this, R.string.e_perms, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------- controls

    private fun wireControls() {
        ui.btnStartStop.setOnClickListener { toggleStreaming() }
        ui.stopButton.setOnClickListener { toggleStreaming() }
        ui.btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        ui.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        ui.cameraMoreToggle.setOnClickListener {
            startActivity(Intent(this, InfoActivity::class.java))
        }
        ui.btnAppSettings.setOnClickListener {
            startActivity(Intent(this, InfoActivity::class.java))
        }
        ui.btnPermError.setOnClickListener { requestPermissions() }

        ui.micToggle.setOnClickListener {
            service?.apply { it.micEnabled = !it.micEnabled }
            refreshUi()
        }
        ui.cameraSwapButton.setOnClickListener {
            val s = service ?: return@setOnClickListener
            val ids = s.cameraEngine().cameraIds()
            if (ids.size < 2) {
                toast(getString(R.string.c))
                return@setOnClickListener
            }
            val next = if (s.activeCameraIndex() == 0) 1 else 0
            s.switchCamera(next)
            refreshUi()
        }
        ui.cameraFlash.setOnClickListener {
            service?.apply { it.torch = !it.torch }
            refreshUi()
        }
        ui.cameraHflip.setOnClickListener {
            prefs.mirrorFront = !prefs.mirrorFront
            service?.updateFromPrefs()
            refreshUi()
        }
        ui.cameraVflip.setOnClickListener {
            prefs.flipVertical = !prefs.flipVertical
            service?.updateFromPrefs()
            refreshUi()
        }
        ui.gridToggle.setOnClickListener {
            prefs.grid = !prefs.grid
            toast(if (prefs.grid) "Grid on" else "Grid off")
        }
        ui.cameraCrop.setOnClickListener { showPanel(Panel.NONE) }

        ui.wbButton.setOnClickListener { showPanel(Panel.WB) }
        ui.isoButton.setOnClickListener { showPanel(Panel.ISO) }
        ui.ssButton.setOnClickListener { showPanel(Panel.SS) }
        ui.evButton.setOnClickListener { showPanel(Panel.EV) }
        ui.afButton.setOnClickListener { cycleAutofocus() }
        ui.zoomButton.setOnClickListener { showPanel(Panel.ZOOM) }

        ui.autoButton.setOnClickListener { autoForPanel() }
        ui.lockButton.setOnClickListener { lockForPanel() }

        ui.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyPanel(progress, commit = false)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                applyPanel(sb?.progress ?: 0, commit = true)
            }
        })
    }

    private fun toggleStreaming() {
        if (service?.isStreaming() == true) {
            startService(Intent(this, CamService::class.java).setAction(CamService.ACTION_STOP))
            toast(getString(R.string.server_stopped))
        } else {
            startAndBind()
        }
        refreshUi()
    }

    // ----------------------------------------------------------------- slider panel

    private fun showPanel(next: Panel) {
        panel = if (panel == next) Panel.NONE else next
        val s = service?.currentSettings() ?: return

        if (panel == Panel.NONE) {
            ui.manualAdjust.visibility = View.GONE
            return
        }
        ui.manualAdjust.visibility = View.VISIBLE

        val (max, value, label, showLock) = when (panel) {
            Panel.ZOOM -> Quad(1000, (s.zoom * 1000).toInt(), getString(R.string.zoom), false)
            Panel.EV -> Quad(64, ((s.ev + 4f) * 8).toInt(), getString(R.string.ev), true)
            Panel.ISO -> Quad(6400, s.iso, getString(R.string.iso), false)
            Panel.SS -> Quad(500, (s.exposureNs / 1_000_000L).toInt(), getString(R.string.ss), false)
            Panel.MF -> Quad(1000, (s.manualFocus * 1000).toInt(), getString(R.string.mf), true)
            Panel.WB -> Quad(1000, (s.wbLevel * 1000).toInt(), getString(R.string.wb), true)
            Panel.NONE -> Quad(100, 0, "", false)
        }
        ui.seekBar.max = max.coerceAtLeast(1)
        ui.seekBar.progress = value.coerceIn(0, ui.seekBar.max)
        ui.seekLabel.text = label
        ui.lockButton.visibility = if (showLock) View.VISIBLE else View.GONE
        ui.lockButton.text = when {
            panel == Panel.EV && s.aeLock -> getString(R.string.unlock)
            panel == Panel.WB && s.wbLock -> getString(R.string.unlock)
            panel == Panel.EV -> getString(R.string.lock)
            panel == Panel.WB -> getString(R.string.lock)
            else -> getString(R.string.lock)
        }
    }

    private data class Quad(val max: Int, val value: Int, val label: String, val showLock: Boolean)

    private fun applyPanel(progress: Int, commit: Boolean) {
        val s = service ?: return
        when (panel) {
            Panel.ZOOM -> s.apply { it.zoom = progress / 1000f }
            Panel.EV -> s.apply { it.ev = progress / 8f - 4f }
            Panel.ISO -> s.apply { it.iso = progress }
            Panel.SS -> s.apply { it.exposureNs = progress * 1_000_000L }
            Panel.MF -> s.apply { it.manualFocus = progress / 1000f }
            Panel.WB -> s.apply { it.wbLevel = progress / 1000f }
            Panel.NONE -> return
        }
        if (commit) refreshUi()
    }

    private fun autoForPanel() {
        val s = service ?: return
        when (panel) {
            Panel.ZOOM -> s.apply { it.zoom = 0f }
            Panel.EV -> s.apply { it.ev = 0f; it.aeLock = false }
            Panel.ISO -> s.apply { it.iso = 0 }
            Panel.SS -> s.apply { it.exposureNs = 0L }
            Panel.MF -> s.apply { it.manualFocus = 0f }
            Panel.WB -> s.apply { it.wbLevel = 0.5f; it.wbLock = false }
            Panel.NONE -> return
        }
        refreshUi()
        showPanel(panel)
    }

    private fun lockForPanel() {
        val s = service ?: return
        when (panel) {
            Panel.EV -> s.apply { it.aeLock = !it.aeLock }
            Panel.WB -> s.apply { it.wbLock = !it.wbLock }
            else -> return
        }
        refreshUi()
        showPanel(panel)
    }

    private fun cycleAutofocus() {
        val s = service ?: return
        // AF button: first press focuses, later presses cycle the AF mode.
        s.apply { }
        s.cameraEngine().triggerAutofocus()
        toast("Autofocus")
        refreshUi()
    }

    // ------------------------------------------------------------------ rendering

    private fun refreshUi() {
        val svc = service
        val running = svc?.isStreaming() == true

        ui.infoText.text = if (running) {
            "${svc?.adderssString()}\n${getString(R.string.server_running)}"
        } else {
            getString(R.string.app_name)
        }
        ui.infoDetail.text = if (running) {
            getString(R.string.connect_hint)
        } else {
            getString(R.string.idle_hint)
        }
        ui.btnStartStop.text = getString(if (running) R.string.stop else R.string.start)
        ui.btnStartStop.setCompoundDrawablesWithIntrinsicBounds(
            if (running) R.drawable.ic_stop_circle else R.drawable.ic_play, 0, 0, 0
        )
        ui.stopButton.text = getString(if (running) R.string.stop else R.string.start)

        ui.wifiRemoteInfo.visibility = if (running) View.VISIBLE else View.INVISIBLE
        ui.wifiRemoteInfo.text = svc?.adderssString().orEmpty()

        val clients = svc?.activeClients() ?: 0
        val fps = svc?.frameStats()?.frameRate ?: 0
        val kbps = svc?.frameStats()?.bitrateKbps ?: 0
        ui.videoStats.visibility = if (running) View.VISIBLE else View.INVISIBLE
        ui.videoStats.text = "$fps fps  $kbps kbps  ${clients} client" + if (clients == 1) "" else "s"

        ui.connectionInfoPeer.visibility = if (clients > 0) View.VISIBLE else View.INVISIBLE
        ui.connectionInfoPeer.text = "$clients connected"
        ui.tallyInfo.setImageResource(
            when (svc?.tallyState()) {
                1 -> R.drawable.ic_tally_program
                2 -> R.drawable.ic_tally_preview
                else -> R.drawable.ic_tally_idle
            }
        )

        val error = svc?.error()
        if (!running && error != null) {
            ui.infoDetail.text = error
        }

        ui.buttonBarBottom.visibility = if (running) View.VISIBLE else View.INVISIBLE
        ui.micToggle.setImageResource(
            if (svc?.currentSettings()?.micEnabled == true) R.drawable.ic_mic_on
            else R.drawable.ic_mic_off
        )
        ui.cameraFlash.setImageResource(
            if (svc?.currentSettings()?.torch == true) R.drawable.ic_flash_on
            else R.drawable.ic_flash_off
        )
        ui.cameraSurface.visibility = if (running) View.VISIBLE else View.INVISIBLE
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQ_PERMS = 4748
    }
}
