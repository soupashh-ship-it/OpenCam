package app.opencam.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 encoder backing the stock client's avc format.
 *
 * Frames arrive on the codec's input [Surface], which is added to the same
 * capture session that feeds the JPEG path, so no pixels are copied. The output
 * buffers are annex-B access units: exactly what the client feeds its decoder
 * after the SPS+PPS config packet, and the csd-0 buffer of the encoder's output
 * format is the annex-B SPS+PPS pair that packet carries.
 */
class AvcEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int
) {

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var drainThread: Thread? = null
    private val info = MediaCodec.BufferInfo()

    @Volatile private var running = false

    /** One access unit (annex-B), called from the drain thread. */
    var onAccessUnit: ((data: ByteArray, keyframe: Boolean, ptsUs: Long) -> Unit)? = null

    /** The SPS+PPS pair from the encoder's output format, called once. */
    var onConfig: ((csd: ByteArray) -> Unit)? = null

    val isRunning: Boolean get() = running

    /** Input surface for the capture session, or null when the codec is down. */
    val inputSurface: Surface? get() = surface

    fun start(): Boolean {
        if (running) return true
        return try {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                // A key frame every second keeps the client's five second start-up
                // window comfortable even on slow software encoders.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val c = MediaCodec.createEncoderByType(MIME)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = c.createInputSurface()
            c.start()
            codec = c
            running = true
            drainThread = Thread({ drainLoop() }, "opencam-avc").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "H.264 encoder unavailable", e)
            surface = null
            codec = null
            running = false
            false
        }
    }

    private fun drainLoop() {
        var frames = 0L
        var lastSyncRequest = 0L
        while (running) {
            val c = codec ?: break
            try {
                // Software encoders honour KEY_I_FRAME_INTERVAL in frames of the
                // declared rate, which can be many seconds at slow encode rates.
                // Asking for a sync frame directly keeps them roughly a second
                // apart regardless of how fast the encoder actually runs.
                val now = System.currentTimeMillis()
                if (now - lastSyncRequest >= SYNC_FRAME_INTERVAL_MS) {
                    lastSyncRequest = now
                    try {
                        val params = Bundle()
                        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        c.setParameters(params)
                    } catch (_: Exception) {
                    }
                }
                val index = c.dequeueOutputBuffer(info, 10_000L)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // csd-0 is the annex-B SPS+PPS the client wants up front.
                        val csd: ByteBuffer? = c.outputFormat.getByteBuffer("csd-0")
                        if (csd != null && csd.remaining() > 0) {
                            val bytes = ByteArray(csd.remaining())
                            csd.get(bytes)
                            Log.i(TAG, "output format, csd=${bytes.size} bytes")
                            onConfig?.invoke(bytes)
                        }
                    }
                    index >= 0 -> {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val buf = c.getOutputBuffer(index)
                        if (buf != null && info.size > 0 && !isConfig) {
                            val data = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(data)
                            val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            if (++frames % 60 == 0L) {
                                Log.i(TAG, "encoded $frames frames")
                            }
                            onAccessUnit?.invoke(data, keyframe, info.presentationTimeUs)
                        }
                        c.releaseOutputBuffer(index, false)
                    }
                }
            } catch (e: IllegalStateException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "avc drain failed", e)
            }
        }
    }

    fun stop() {
        running = false
        drainThread?.interrupt()
        drainThread = null
        val c = codec
        codec = null
        surface = null
        if (c != null) {
            try {
                c.stop()
            } catch (_: Exception) {
            }
            try {
                c.release()
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val MIME = "video/avc"
        const val TAG = "OpenCam/Avc"
        const val SYNC_FRAME_INTERVAL_MS = 1_000L
    }
}
