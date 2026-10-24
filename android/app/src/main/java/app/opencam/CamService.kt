package app.opencam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.opencam.core.AudioEngine
import app.opencam.core.CameraEngine
import app.opencam.core.CamCaps
import app.opencam.core.CameraSettings
import app.opencam.core.ControlSurface
import app.opencam.core.Discovery
import app.opencam.core.HttpServer
import app.opencam.core.StreamHub

/**
 * Runs everything the desktop client talks to: Camera2 capture, microphone
 * capture, the HTTP streaming server and the mDNS advertisement.
 *
 * Streaming starts as soon as the service starts, which is what the reference
 * client does as well.
 */
class CamService : Service(), ControlSurface {

    companion object {
        const val ACTION_START = "app.opencam.action.START"
        const val ACTION_STOP = "app.opencam.action.STOP"
        const val ACTION_TOGGLE_MIC = "app.opencam.action.TOGGLE_MIC"
        const val ACTION_TOGGLE_TORCH = "app.opencam.action.TOGGLE_TORCH"
        const val EXTRA_SIZE = "size"

        const val BROADCAST_STATE = "app.opencam.STATE"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_CLIENTS = "clients"
        const val EXTRA_FPS = "fps"
        const val EXTRA_ERROR = "error"
        const val EXTRA_STATS = "stats"

        private const val CHANNEL_ID = "opencam_stream"
        private const val NOTIFICATION_ID = 4747
        private const val TAG = "OpenCam/Service"

        @Volatile var running = false
            private set
    }

    inner class LocalBinder : Binder() {
        val service: CamService get() = this@CamService
    }

    private val binder = LocalBinder()

    private lateinit var prefs: Prefs
    private lateinit var hub: StreamHub
    private lateinit var camera: CameraEngine
    private lateinit var audio: AudioEngine
    private lateinit var server: HttpServer
    private lateinit var discovery: Discovery

    @Volatile private var tally = 0
    @Volatile private var lastError: String? = null
    @Volatile private var address: String = ""
    @Volatile private var activePort = -1

    private var previewAttached = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        hub = StreamHub()
        camera = CameraEngine(this)
        audio = AudioEngine(hub)
        server = HttpServer(hub, this)
        discovery = Discovery(this)

        camera.listener = object : CameraEngine.Listener {
            override fun onFrame(jpeg: ByteArray, width: Int, height: Int) {
                hub.publishVideo(jpeg, width, height)
            }

            override fun onEngineState(text: String) {
                broadcastState()
            }

            override fun onEngineError(message: String) {
                lastError = message
                Log.w(TAG, "camera: $message")
                broadcastState()
            }

            override fun onSettingsChanged(settings: CameraSettings) {
                broadcastState()
            }

            override fun onEncodedFrame(data: ByteArray, keyframe: Boolean, ptsUs: Long) {
                hub.publishEncoded(data, keyframe, ptsUs)
            }

            override fun onAvcConfig(csd: ByteArray) {
                hub.publishAvcConfig(csd)
            }
        }
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardownStreaming()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MIC -> {
                apply { it.micEnabled = !it.micEnabled }
                syncAudio()
                broadcastState()
                return START_STICKY
            }
            ACTION_TOGGLE_TORCH -> {
                apply { it.torch = !it.torch }
                broadcastState()
                return START_STICKY
            }
            else -> {
                startStreaming()
                return START_STICKY
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private fun startStreaming() {
        if (running) {
            // Re-read preferences and apply whatever changed.
            updateFromPrefs()
            return
        }
        lastError = null

        startForegroundCompat()

        val preset = CamCaps.preset(prefs.resolution)
        camera.start(prefs.cameraFacing, preset.width, preset.height, prefs.fps)
        camera.settings.mirror = prefs.mirrorFront
        camera.settings.flipVertical = prefs.flipVertical
        camera.settings.micEnabled = prefs.audioEnabled
        camera.applySettings(camera.settings)

        if (prefs.audioEnabled) audio.start()

        val port = prefs.port
        if (!server.start(port)) {
            lastError = server.lastError ?: "Could not listen on port $port"
            Log.e(TAG, "server failed: $lastError")
        }
        activePort = port

        address = "${localAddress()}:$port"

        if (prefs.discovery) {
            discovery.advertise(
                name = "${android.os.Build.MODEL} OpenCam",
                port = port,
                deviceName = deviceName()
            )
        }

        running = true
        broadcastState()
    }

    override fun stopStreaming() {
        teardownStreaming()
    }

    private fun teardownStreaming() {
        running = false
        discovery.stop()
        server.stop()
        audio.stop()
        camera.stop()
        hub.closeAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcastState()
    }

    override fun onDestroy() {
        running = false
        try {
            discovery.stop()
            server.stop()
            audio.stop()
            camera.stop()
            hub.closeAll()
        } catch (e: Exception) {
            Log.w(TAG, "teardown", e)
        }
        super.onDestroy()
        broadcastState()
    }

    // ------------------------------------------------------------- UI attach/detach

    fun attachPreview(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
        previewAttached = true
        // The engine rebuilds the capture session itself when a session is
        // already running; the camera device stays open so streaming resumes
        // without a full open/close cycle.
        camera.attachPreview(texture, width, height)
    }

    fun detachPreview() {
        if (!previewAttached) return
        previewAttached = false
        // The engine drops the dead output surface by rebuilding the session.
        camera.detachPreview()
    }

    fun currentSettings(): CameraSettings = camera.settings
    fun frameStats(): CameraEngine.FrameStats = camera.stats
    fun activeClients(): Int = server.clientCount
    fun adderssString(): String = address
    fun error(): String? = lastError
    fun isStreaming(): Boolean = running
    fun cameraEngine(): CameraEngine = camera

    fun updateFromPrefs() {
        if (!running) return

        // A port change needs the listener to move; everything else is live.
        if (prefs.port != activePort) {
            server.stop()
            if (server.start(prefs.port)) {
                activePort = prefs.port
                lastError = null
            } else {
                lastError = server.lastError ?: "Could not listen on port ${prefs.port}"
            }
            address = "${localAddress()}:$activePort"
        }

        val preset = CamCaps.preset(prefs.resolution)
        val facing = prefs.cameraFacing
        val changed = camera.settings.facing != facing ||
            camera.settings.width != preset.width ||
            camera.settings.height != preset.height ||
            camera.settings.fps != prefs.fps
        if (changed) {
            camera.restart(facing, preset.width, preset.height, prefs.fps)
        }
        camera.settings.mirror = prefs.mirrorFront
        camera.settings.flipVertical = prefs.flipVertical
        camera.applySettings(camera.settings)

        if (prefs.audioEnabled && !audio.isRunning) audio.start()
        if (!prefs.audioEnabled && audio.isRunning) audio.stop()

        if (prefs.discovery && !discovery.isAdvertising) {
            discovery.advertise(
                "${android.os.Build.MODEL} OpenCam", activePort, deviceName()
            )
        } else if (!prefs.discovery) {
            discovery.stop()
        }
        broadcastState()
    }

    private fun syncAudio() {
        val wantMic = camera.settings.micEnabled
        if (wantMic && !audio.isRunning) audio.start()
        if (!wantMic && audio.isRunning) audio.stop()
    }

    // ---------------------------------------------------------------- ControlSurface

    override fun settings(): CameraSettings = camera.settings

    override fun apply(mutate: (CameraSettings) -> Unit) {
        val s = camera.settings
        mutate(s)
        camera.applySettings(s)
        if (s.micEnabled != audio.isRunning) syncAudio()
        broadcastState()
    }

    override fun switchCamera(index: Int) {
        val ids = camera.cameraIds()
        if (ids.isEmpty()) return
        val target = ids[index.coerceIn(0, ids.lastIndex)]
        val preset = CamCaps.preset(prefs.resolution)
        camera.settings.facing = target.second
        prefs.cameraFacing = target.second
        camera.restart(target.second, preset.width, preset.height, prefs.fps)
        broadcastState()
    }

    override fun requestSize(width: Int, height: Int) {
        val w = width.coerceIn(160, 4096)
        val h = height.coerceIn(120, 4096)
        camera.settings.width = w
        camera.settings.height = h
        camera.restart(camera.settings.facing, w, h, prefs.fps)
        broadcastState()
    }

    override fun restartStreaming() {
        if (!running) return
        val preset = CamCaps.preset(prefs.resolution)
        camera.restart(prefs.cameraFacing, preset.width, preset.height, prefs.fps)
        broadcastState()
    }

    override fun cameraList(): List<String> =
        camera.cameraIds().map { (id, facing) ->
            val label = when (facing) {
                android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT -> "front"
                android.hardware.camera2.CameraMetadata.LENS_FACING_BACK -> "back"
                else -> "external"
            }
            "$id:$label"
        }

    override fun activeCameraIndex(): Int {
        val ids = camera.cameraIds()
        val active = camera.activeCameraId() ?: return 0
        val idx = ids.indexOfFirst { it.first == active }
        return if (idx < 0) 0 else idx
    }

    override fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    override fun batteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    }

    override fun isCharging(): Boolean {
        val intent = registerReceiver(null, android.content.IntentFilter(
            android.content.Intent.ACTION_BATTERY_CHANGED
        )) ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun setTally(state: Int) {
        tally = state
        broadcastState()
    }

    override fun tallyState(): Int = tally

    override fun port(): Int = if (activePort > 0) activePort else prefs.port

    // ---------------------------------------------------------------- notifications

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.connect_hint)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_webcam)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, prefs.port))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (prefs.audioEnabled) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // -------------------------------------------------------------------- helpers

    /** Best-effort LAN IPv4 address for the "connect to" hint. */
    private fun localAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (nif in java.util.Collections.list(interfaces)) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in java.util.Collections.list(nif.inetAddresses)) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "localAddress", e)
        }
        return "0.0.0.0"
    }

    private fun broadcastState() {
        val intent = Intent(BROADCAST_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, running)
            putExtra(EXTRA_ADDRESS, address)
            putExtra(EXTRA_CLIENTS, server.clientCount)
            putExtra(EXTRA_FPS, camera.stats.frameRate)
            putExtra(EXTRA_STATS, camera.stats.bitrateKbps)
            lastError?.let { putExtra(EXTRA_ERROR, it) }
        }
        sendBroadcast(intent)
    }
}
