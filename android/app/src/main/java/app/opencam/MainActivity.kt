package app.opencam

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.SeekBar
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
 *
 * The preview follows the reference app: the camera buffer is laid out edge to
 * edge, aspect-preserved with letterboxing by default, or centre-cropped when
 * the crop toggle selects fill mode. Mirroring and vertical flipping are applied
 * to both the preview and the outgoing stream.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var service: CamService? = null
    private var bound = false

    /** Which parameter the bottom slider is currently editing. */
    private enum class Panel { NONE, ZOOM, EV, ISO, SS, MF, WB }

    private var panel = Panel.NONE

    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastRunning: Boolean? = null
    private var hintVisible = false
    private var hintScheduled = false

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
            ui.cameraSurface.surfaceTexture?.let { attachPreview(it) }
            refreshUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
            refreshUi()
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            attachPreview(texture)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            applyPreviewGeometry()
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            service?.detachPreview()
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        }
    }

    /** Keeps the fps / bitrate readout live instead of only updating on events. */
    private val statsTicker = object : Runnable {
        override fun run() {
            refreshUi()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private val fadeHint = Runnable {
        hintScheduled = false
        if (!hintVisible) return@Runnable
        ui.connectHintImg.animate().alpha(1f).setDuration(250L).start()
        ui.connectHintTxt.animate().alpha(1f).setDuration(250L).start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        prefs = Prefs(this)

        ui.cameraSurface.surfaceTextureListener = surfaceTextureListener

        wireControls()
        collapseExtraControls()
        applyPreviewGeometry()
        refreshUi()

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
        uiHandler.removeCallbacks(statsTicker)
        uiHandler.post(statsTicker)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(statsTicker)
        if (hintScheduled) {
            uiHandler.removeCallbacks(fadeHint)
            hintScheduled = false
            hintVisible = false
            ui.connectHintImg.visibility = View.INVISIBLE
            ui.connectHintTxt.visibility = View.INVISIBLE
        }
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(statsTicker)
        uiHandler.removeCallbacks(fadeHint)
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
        if (!bound) bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun attachPreview(texture: SurfaceTexture) {
        val svc = service ?: return
        val settings = svc.currentSettings()
        svc.attachPreview(texture, settings.width, settings.height)
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
        refreshUi()
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
        ui.cameraMoreToggle.setOnClickListener { revealExtraControls() }
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
            applyPreviewGeometry()
            refreshUi()
        }
        ui.cameraVflip.setOnClickListener {
            prefs.flipVertical = !prefs.flipVertical
            service?.updateFromPrefs()
            applyPreviewGeometry()
            refreshUi()
        }
        ui.gridToggle.setOnClickListener {
            prefs.grid = !prefs.grid
            ui.previewLayout.setGridVisible(prefs.grid)
        }
        ui.cameraCrop.setOnClickListener {
            prefs.crop = !prefs.crop
            applyPreviewGeometry()
            toast(getString(if (prefs.crop) R.string.scale_fill else R.string.scale_fit))
        }

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

    private fun extraControls(): List<View> = listOf(
        ui.cameraCrop, ui.gridToggle, ui.cameraVflip, ui.cameraHflip, ui.settingsButton
    )

    private fun revealExtraControls() {
        if (ui.cameraMoreToggle.visibility != View.VISIBLE) return
        ui.cameraMoreToggle.visibility = View.GONE
        val fade = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        fade.startOffset = fade.duration / 2
        fade.duration = fade.duration / 2
        for (view in extraControls()) {
            view.startAnimation(fade)
            view.visibility = View.VISIBLE
        }
    }

    private fun collapseExtraControls() {
        for (view in extraControls()) {
            view.clearAnimation()
            view.visibility = View.GONE
        }
        ui.cameraMoreToggle.visibility = View.VISIBLE
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

    // ------------------------------------------------------------------ geometry

    private fun applyPreviewGeometry() {
        val preset = CamCaps.preset(prefs.resolution)
        val settings = service?.currentSettings()
        val width = settings?.width?.takeIf { it > 0 } ?: preset.width
        val height = settings?.height?.takeIf { it > 0 } ?: preset.height
        ui.previewLayout.setGeometry(
            width,
            height,
            prefs.crop,
            prefs.mirrorFront,
            prefs.flipVertical
        )
        ui.previewLayout.setGridVisible(prefs.grid)
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
        s.cameraEngine().triggerAutofocus()
        toast("Autofocus")
        refreshUi()
    }

    // ------------------------------------------------------------------ rendering

    private fun refreshUi() {
        val svc = service
        val running = svc?.isStreaming() == true

        val clients = svc?.activeClients() ?: 0
        val settings = svc?.currentSettings()
        val error = svc?.error()

        // The idle card is shown before streaming and for error warnings. While
        // the server runs with no client, the reference app relies on the
        // connect hint instead of a permanent card.
        val showInfo = !running || error != null
        ui.infoText.visibility = if (showInfo) View.VISIBLE else View.GONE
        ui.infoText.text = when {
            error != null && running -> error
            error != null -> "${getString(R.string.app_name)}\n$error"
            else -> "${getString(R.string.app_name)}\n${getString(R.string.idle_hint)}"
        }
        // The status strip and the warning card share the top-left corner, so
        // only one of them is drawn at a time.
        ui.detailBar.visibility = if (running && !showInfo) View.VISIBLE else View.INVISIBLE
        ui.btnStartStop.visibility = if (running) View.GONE else View.VISIBLE
        ui.btnOpenSettings.visibility = if (running) View.GONE else View.VISIBLE

        ui.buttonBarTop.visibility = if (running) View.VISIBLE else View.GONE
        if (lastRunning != running) {
            lastRunning = running
            collapseExtraControls()
        }
        ui.micToggle.visibility = if (running) View.VISIBLE else View.INVISIBLE
        ui.stopButton.visibility = if (running) View.VISIBLE else View.GONE

        val address = svc?.adderssString().orEmpty()
        ui.wifiRemoteInfo.visibility = if (running && address.isNotEmpty()) {
            View.VISIBLE
        } else {
            View.INVISIBLE
        }
        ui.wifiRemoteInfo.text = if (address.isNotEmpty()) "http://$address" else ""

        val fps = svc?.frameStats()?.frameRate ?: 0
        val kbps = svc?.frameStats()?.bitrateKbps ?: 0
        val width = settings?.width ?: 0
        val height = settings?.height ?: 0
        ui.videoStats.visibility = if (running) View.VISIBLE else View.INVISIBLE
        ui.videoStats.text = "Video: ${width}x${height}\n$fps fps  $kbps kbps"

        ui.connectionInfoPeer.visibility = if (clients > 0) View.VISIBLE else View.INVISIBLE
        ui.connectionInfoPeer.text = when (clients) {
            0 -> ""
            1 -> "1 client"
            else -> "$clients clients"
        }
        ui.tallyInfo.visibility = if (clients > 0) View.VISIBLE else View.GONE
        ui.tallyInfo.setCompoundDrawablesRelativeWithIntrinsicBounds(
            when (svc?.tallyState()) {
                1 -> R.drawable.ic_tally_program
                2 -> R.drawable.ic_tally_preview
                else -> R.drawable.ic_tally_idle
            },
            0, 0, 0
        )

        ui.buttonBarBottom.visibility = if (running) View.VISIBLE else View.INVISIBLE
        ui.micToggle.setImageResource(
            if (settings?.micEnabled == true) R.drawable.ic_mic_on
            else R.drawable.ic_mic_off
        )
        ui.cameraFlash.setImageResource(
            if (settings?.torch == true) R.drawable.ic_flash_on
            else R.drawable.ic_flash_off
        )

        applyPreviewGeometry()
        setConnectHint(clients == 0 && hasPermissions() && error == null)
    }

    private fun setConnectHint(show: Boolean) {
        if (show) {
            if (hintVisible || hintScheduled) return
            hintVisible = true
            hintScheduled = true
            ui.connectHintImg.animate().cancel()
            ui.connectHintTxt.animate().cancel()
            ui.connectHintImg.alpha = 0f
            ui.connectHintTxt.alpha = 0f
            ui.connectHintImg.visibility = View.VISIBLE
            ui.connectHintTxt.visibility = View.VISIBLE
            uiHandler.postDelayed(fadeHint, 1200L)
        } else {
            if (!hintVisible && !hintScheduled) return
            hintVisible = false
            hintScheduled = false
            uiHandler.removeCallbacks(fadeHint)
            ui.connectHintImg.animate().cancel()
            ui.connectHintTxt.animate().cancel()
            ui.connectHintImg.alpha = 0f
            ui.connectHintTxt.alpha = 0f
            ui.connectHintImg.visibility = View.INVISIBLE
            ui.connectHintTxt.visibility = View.INVISIBLE
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQ_PERMS = 4748
    }
}
