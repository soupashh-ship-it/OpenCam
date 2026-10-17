package app.opencam.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Owns the Camera2 device and turns it into a stream of upright JPEG frames.
 *
 * Frames are produced by an [ImageReader] in JPEG format (hardware encoded), then
 * rotated to display orientation and optionally mirrored. Rotation is required
 * because MJPEG consumers read raw pixels and ignore the EXIF orientation tag.
 */
class CameraEngine(private val context: Context) {

    interface Listener {
        fun onFrame(jpeg: ByteArray, width: Int, height: Int)
        fun onEngineState(text: String)
        fun onEngineError(message: String)
        fun onSettingsChanged(settings: CameraSettings)

        /** One encoded H.264 access unit (annex-B), for the stock client's avc format. */
        fun onEncodedFrame(data: ByteArray, keyframe: Boolean, ptsUs: Long) {}

        /** The SPS+PPS pair, sent to avc clients as their config packet. */
        fun onAvcConfig(csd: ByteArray) {}
    }

    var listener: Listener? = null

    val settings = CameraSettings()

    private val manager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var chars: CameraCharacteristics? = null
    private var avc: AvcEncoder? = null

    private var previewSurface: Surface? = null
    private var previewHolder: SurfaceHolder? = null

    private val released = AtomicBoolean(true)
    private val frames = FrameStats()

    private var jpegQuality = 84
    private var transformCache: Bitmap? = null
    private val transformBuffer = ByteArrayOutputStream(512 * 1024)

    @Volatile private var rotationDegrees = 0

    @Volatile private var cachedRotation = Surface.ROTATION_0

    val stats: FrameStats get() = frames

    // ---------------------------------------------------------------- lifecycle

    fun attachPreview(holder: SurfaceHolder) {
        previewHolder = holder
        previewSurface = holder.surface
    }

    fun detachPreview() {
        previewSurface = null
    }

    /**
     * Opens the selected camera. [facing] is a [CameraMetadata] LENS_FACING value;
     * if that facing is unavailable the first camera in the list is used.
     */
    @SuppressLint("MissingPermission")
    fun start(facing: Int, width: Int, height: Int, fps: Int) {
        if (!released.compareAndSet(true, false)) {
            // Already running: only a geometry or camera change matters.
            if (settings.width != width || settings.height != height ||
                settings.fps != fps || settings.facing != facing
            ) {
                restart(facing, width, height, fps)
            }
            return
        }
        settings.facing = facing
        settings.width = width
        settings.height = height
        settings.fps = fps
        startThread()
        openCamera()
    }

    fun stop() {
        if (!released.compareAndSet(false, true)) return
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null
        reader?.close()
        reader = null
        avc?.stop()
        avc = null
        chars = null
        stopThread()
    }

    fun restart(facing: Int, width: Int, height: Int, fps: Int) {
        stop()
        settings.facing = facing
        settings.width = width
        settings.height = height
        settings.fps = fps
        start(facing, width, height, fps)
    }

    private fun startThread() {
        if (thread != null) return
        thread = HandlerThread("opencam-camera").also { it.start() }
        handler = Handler(thread!!.looper)
    }

    private fun stopThread() {
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // ------------------------------------------------------------------ devices

    /** Camera ids paired with their facing, ordered back-facing first. */
    fun cameraIds(): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        try {
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING)
                    ?: CameraMetadata.LENS_FACING_BACK
                out.add(id to facing)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cameraIds failed", e)
        }
        return out.sortedBy { if (it.second == CameraMetadata.LENS_FACING_BACK) 0 else 1 }
    }

    private fun pickCameraId(facing: Int): String? {
        val ids = cameraIds()
        return ids.firstOrNull { it.second == facing }?.first ?: ids.firstOrNull()?.first
    }

    fun activeCameraId(): String? = settings.cameraId

    /** Descriptive one-liner used by the control endpoint and the info panel. */
    fun describeActive(): String {
        val c = chars ?: return "unknown"
        val lens = when (settings.facing) {
            CameraMetadata.LENS_FACING_FRONT -> "front"
            CameraMetadata.LENS_FACING_BACK -> "back"
            else -> "external"
        }
        val flash = if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) "flash" else "no-flash"
        return "$lens ${settings.width}x${settings.height}@${settings.fps} $flash"
    }

    // -------------------------------------------------------------------- setup

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val id = pickCameraId(settings.facing)
        if (id == null) {
            listener?.onEngineError("No camera available")
            return
        }
        settings.cameraId = id
        try {
            chars = manager.getCameraCharacteristics(id)
            val map = chars!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val size = CamCaps.chooseSize(
                    map, CamCaps.Preset("c", settings.width, settings.height)
                )
                settings.width = size.width
                settings.height = size.height
            }
            manager.openCamera(id, stateCallback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera", e)
            listener?.onEngineError(e.message ?: "Camera open failed")
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            createSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            device = null
            listener?.onEngineError("Camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            device = null
            listener?.onEngineError("Camera error $error")
        }
    }

    private fun createSession() {
        val cam = device ?: return
        val readerLocal = ImageReader.newInstance(
            settings.width, settings.height, android.graphics.ImageFormat.JPEG, 3
        )
        reader = readerLocal
        readerLocal.setOnImageAvailableListener({ r -> onImageAvailable(r) }, handler)

        val surfaces = ArrayList<Surface>(3)
        previewSurface?.let { if (it.isValid) surfaces.add(it) }
        surfaces.add(readerLocal.surface)

        // The H.264 encoder shares the capture session; its surface feeds the
        // stock client's avc format while the reader feeds the JPEG path.
        val encoder = AvcEncoder(settings.width, settings.height, settings.fps, avcBitrate())
        encoder.onConfig = { listener?.onAvcConfig(it) }
        encoder.onAccessUnit = { data, key, pts -> listener?.onEncodedFrame(data, key, pts) }
        val started = encoder.start()
        avc = if (started) encoder else null
        if (started) encoder.inputSurface?.let { surfaces.add(it) }

        try {
            cam.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    applySettings(settings)
                    listener?.onEngineState("streaming")
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    listener?.onEngineError("Capture session failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "createSession", e)
            listener?.onEngineError(e.message ?: "Session failed")
        }
    }

    /**
     * Rebuilds the repeating request from [s] and pushes it to the session.
     * Safe to call from the UI thread.
     */
    fun applySettings(s: CameraSettings) {
        val cam = device ?: return
        val sess = session ?: return
        val c = chars ?: return
        val h = handler ?: return
        h.post {
            // A client can disconnect a control change in flight, in which case the
            // device has already been closed and a repeating request would throw.
            if (released.get()) return@post
            try {
                val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                previewSurface?.let { builder.addTarget(it) }
                reader?.let { builder.addTarget(it.surface) }
                avc?.inputSurface?.let { builder.addTarget(it) }
                populate(builder, c, s)
                sess.setRepeatingRequest(builder.build(), null, h)
                listener?.onSettingsChanged(s)
            } catch (e: Exception) {
                Log.e(TAG, "applySettings", e)
            }
        }
    }

    private fun populate(b: CaptureRequest.Builder, c: CameraCharacteristics, s: CameraSettings) {
        val area = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect()

        // --- zoom -------------------------------------------------------------
        val maxZoom = CamCaps.maxZoom(c)
        val zoom = s.zoom.coerceIn(0f, 1f)
        val ratio = 1f + zoom * (maxZoom - 1f)
        if (ratio > 1.001f) {
            val cx = area.width() / 2
            val cy = area.height() / 2
            val halfW = (area.width() / (2f * ratio)).roundToInt()
            val halfH = (area.height() / (2f * ratio)).roundToInt()
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(cx - halfW, cy - halfH, cx + halfW, cy + halfH))
        } else {
            b.set(CaptureRequest.SCALER_CROP_REGION, area)
        }

        // --- exposure ---------------------------------------------------------
        val isoRange = CamCaps.isoRange(c)
        val wantsManual = s.iso > 0 || s.exposureNs > 0L
        if (wantsManual) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            if (s.iso > 0) b.set(CaptureRequest.SENSOR_SENSITIVITY, s.iso.coerceIn(isoRange.first, isoRange.last))
            if (s.exposureNs > 0L) {
                val lo = CamCaps.minExposure(c)
                val hi = CamCaps.maxExposure(c)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureNs.coerceIn(lo, hi))
            }
        } else {
            val maxEv = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.upper ?: 0
            val minEv = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.lower ?: 0
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                s.ev.roundToInt().coerceIn(minEv, maxEv)
            )
        }
        val fpsRange = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        if (fpsRange != null && fpsRange.isNotEmpty()) {
            val wanted = fpsRange
                .filter { it.upper >= s.fps }
                .minByOrNull { it.upper }
                ?: fpsRange.maxByOrNull { it.upper }
            if (wanted != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, wanted)
        }
        b.set(CaptureRequest.CONTROL_AE_LOCK, s.aeLock)

        // --- white balance ----------------------------------------------------
        val awbAvailable = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        if (awbAvailable != null && awbAvailable.contains(s.wbMode)) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, s.wbMode)
        }
        if (s.wbLock) {
            b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        if (s.wbMode == CameraMetadata.CONTROL_AWB_MODE_AUTO && s.wbLevel != 0.5f) {
            // Manual white balance: bias the channel gains around neutral. 0.5 is
            // unchanged, below it is warmer, above it is cooler.
            val bias = (s.wbLevel - 0.5f) * 2f
            val red = (1f + bias * 0.6f).coerceIn(0.2f, 4f)
            val blue = (1f - bias * 0.6f).coerceIn(0.2f, 4f)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            b.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                RggbChannelVector(red, 1f, 1f, blue)
            )
            b.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
        }

        // --- focus ------------------------------------------------------------
        val afAvailable = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        if (isFront(c)) {
            // Fixed-focus front sensors reject any AF mode other than OFF/EDOF.
            val usable = if (afAvailable != null &&
                (afAvailable.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE))
            ) s.afMode else CameraMetadata.CONTROL_AF_MODE_OFF
            b.set(CaptureRequest.CONTROL_AF_MODE, usable)
        } else if (afAvailable != null && afAvailable.contains(s.afMode)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, s.afMode)
        }
        if (s.manualFocus > 0f && focusRange(c) != null) {
            val fr = focusRange(c)!!
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                (fr.lower + (fr.upper - fr.lower) * (1f - s.manualFocus.coerceIn(0f, 1f)))
            )
        }

        // --- flash ------------------------------------------------------------
        val hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        b.set(
            CaptureRequest.FLASH_MODE,
            if (s.torch && hasFlash) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )

        // --- output -----------------------------------------------------------
        //
        // The stream is deliberately left in the sensor's native orientation
        // rather than being rotated to match the device's display rotation:
        //
        //  * A webcam is used with the phone propped up in landscape, which is
        //    the sensor's own orientation, so rotating would give a sideways image.
        //  * Rotating would change the frame shape (a 640x480 request would come
        //    back 480x640), so the size a client asks for would not be the size it
        //    receives.
        //  * Rotating means decoding and re-encoding every frame, which roughly
        //    halves the achievable frame rate for no benefit.
        //
        // Mirror and vertical flip stay available, and only those pay the
        // re-encode cost.
        rotationDegrees = 0
        b.set(CaptureRequest.JPEG_ORIENTATION, 0)
        b.set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
    }

    private fun isFront(c: CameraCharacteristics): Boolean =
        c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT

    private fun focusRange(c: CameraCharacteristics): Range<Float>? =
        c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let { min ->
            if (min <= 0f) null else Range(0f, min)
        }

    /** Bitrate for the H.264 encoder: about 4 Mbit/s per megapixel-second. */
    private fun avcBitrate(): Int =
        (settings.width.toLong() * settings.height * settings.fps / 8).toInt()
            .coerceIn(500_000, 8_000_000)

    /** One-shot autofocus, used by the AF button and the control endpoint. */
    fun triggerAutofocus() {
        val cam = device ?: return
        val sess = session ?: return
        val h = handler ?: return
        h.post {
            try {
                val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                previewSurface?.let { b.addTarget(it) }
                reader?.let { b.addTarget(it.surface) }
                avc?.inputSurface?.let { b.addTarget(it) }
                chars?.let { populate(b, it, settings) }
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                sess.capture(b.build(), null, h)
            } catch (e: Exception) {
                Log.e(TAG, "triggerAutofocus", e)
            }
        }
    }

    // ------------------------------------------------------------------- frames

    private fun onImageAvailable(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquire", e)
            null
        } ?: return

        try {
            val buffer = image.planes[0].buffer
            val raw = ByteArray(buffer.remaining())
            buffer.get(raw)
            val jpeg = transform(raw, image.width, image.height)
            val w = if (rotationDegrees == 90 || rotationDegrees == 270) image.height else image.width
            val h = if (rotationDegrees == 90 || rotationDegrees == 270) image.width else image.height
            frames.record(jpeg.size)
            listener?.onFrame(jpeg, w, h)
        } catch (e: Exception) {
            Log.w(TAG, "frame", e)
        } finally {
            image.close()
        }
    }

    /**
     * Rotates to display orientation and applies the mirror/flip the user asked
     * for. JPEG pixels carry no orientation metadata that MJPEG readers honour,
     * so the transform has to happen here.
     */
    private fun transform(raw: ByteArray, srcW: Int, srcH: Int): ByteArray {
        val rot = rotationDegrees
        val mirror = settings.mirror
        val flipV = settings.flipVertical
        if (rot == 0 && !mirror && !flipV) return raw

        val opts = BitmapFactory.Options().apply {
            inMutable = true
            transformCache?.let { if (!it.isRecycled) inBitmap = it }
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        } catch (e: Exception) {
            Log.w(TAG, "decode frame", e)
            null
        } ?: return raw
        transformCache = decoded

        val m = Matrix()
        m.postRotate(rot.toFloat())
        if (mirror || flipV) m.postScale(if (mirror) -1f else 1f, if (flipV) -1f else 1f)

        val out = try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        } catch (e: Exception) {
            Log.w(TAG, "rotate frame", e)
            return raw
        }

        return try {
            transformBuffer.reset()
            out.compress(Bitmap.CompressFormat.JPEG, jpegQuality, transformBuffer)
            if (out !== transformCache) out.recycle()
            transformBuffer.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "compress frame", e)
            raw
        } finally {
            // decoded is retained as the inBitmap cache for the next frame
        }
    }

    /**
     * Current display rotation.
     *
     * The engine runs inside a Service, whose context is not a visual context, so
     * `Context.getDisplay()` throws there and `WindowManager.getDefaultDisplay()`
     * is unreliable on recent releases. DisplayManager is the documented way to
     * read the default display from any context; the last good value is cached so
     * a transient failure never breaks a capture request.
     */
    private fun displayRotation(): Int {
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val rotation = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            if (rotation != null) {
                cachedRotation = rotation
                return rotation
            }
        } catch (e: Exception) {
            Log.w(TAG, "displayRotation via DisplayManager failed", e)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try {
                @Suppress("DEPRECATION")
                val rotation = windowManager.defaultDisplay.rotation
                cachedRotation = rotation
                return rotation
            } catch (e: Exception) {
                Log.w(TAG, "displayRotation via WindowManager failed", e)
            }
        }
        return cachedRotation
    }

    /** Live frame rate and bit rate, shown in the info overlay and /v1/camera/info. */
    class FrameStats {
        private var windowStart = System.currentTimeMillis()
        private var windowBytes = 0L
        private var windowFrames = 0
        private var fps = 0
        private var kbps = 0

        val frameRate: Int get() = fps
        val bitrateKbps: Int get() = kbps

        fun record(bytes: Int) {
            windowBytes += bytes
            windowFrames++
            val now = System.currentTimeMillis()
            val elapsed = now - windowStart
            if (elapsed >= 1000) {
                fps = (windowFrames * 1000 / elapsed).toInt()
                kbps = (windowBytes * 8 / elapsed).toInt()
                windowStart = now
                windowBytes = 0
                windowFrames = 0
            }
        }

        fun reset() {
            windowStart = System.currentTimeMillis()
            windowBytes = 0
            windowFrames = 0
            fps = 0
            kbps = 0
        }
    }

    private companion object {
        const val TAG = "OpenCam/Camera"
    }
}
