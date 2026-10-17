package app.opencam.core

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log

/**
 * Captures microphone audio as 16-bit mono PCM at 48 kHz and hands blocks to the
 * hub. 48 kHz mono is what both the classic WAV endpoint and the AAC endpoint
 * expect, so a single capture feeds both.
 *
 * Capture uses non-blocking reads, so a stall in the audio stack (which an
 * emulator or a busy phone can produce) shows up as a run of empty reads
 * instead of a read that never returns. After a few seconds of silence the
 * capture is torn down and opened again, which recovers the stream without a
 * restart of the service.
 */
class AudioEngine(private val hub: StreamHub) {

    @Volatile private var record: AudioRecord? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, ENCODING
        )
        if (minBuf <= 0) {
            Log.w(TAG, "microphone unavailable (minBuffer=$minBuf)")
            return false
        }
        val rec = openRecorder(minBuf * 4) ?: return false
        if (!begin(rec)) {
            rec.release()
            return false
        }
        record = rec
        running = true
        // Read a fraction of the internal buffer at a time; the non-blocking
        // mode simply returns whatever has arrived since the last poll.
        val blockSamples = minBuf / 2
        worker = Thread({ loop(blockSamples) }, "opencam-audio").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        Log.i(TAG, "capture started")
        return true
    }

    /**
     * The capture path is a mic feed, but not every device (or emulator)
     * accepts every source, so fall back through the alternatives until one
     * initialises.
     */
    private fun openRecorder(internalBuffer: Int): AudioRecord? {
        for (source in AUDIO_SOURCES) {
            val candidate = try {
                AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, ENCODING, internalBuffer)
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord source $source rejected: ${e.message}")
                null
            }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "capturing from source $source, buffer $internalBuffer bytes")
                return candidate
            }
            candidate?.release()
        }
        Log.w(TAG, "no usable microphone source")
        return null
    }

    private fun begin(rec: AudioRecord): Boolean {
        return try {
            rec.startRecording()
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording", e)
            false
        }
    }

    private fun loop(blockSamples: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = ByteArray(blockSamples * 2)
        var stallMs = 0L
        var attempts = 0
        while (running) {
            val current = record ?: break
            val read = try {
                current.read(block, 0, block.size, AudioRecord.READ_NON_BLOCKING)
            } catch (e: Exception) {
                Log.w(TAG, "read", e)
                -1
            }
            if (read > 0) {
                stallMs = 0
                attempts = 0
                hub.publishAudio(block, read)
            } else {
                stallMs += POLL_MS
                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                }
                // Back off the restarts if the audio stack stays broken, so the
                // log stays readable while it recovers.
                val limit = STALL_RESTART_MS shl attempts.coerceAtMost(3)
                if (stallMs >= limit) {
                    Log.w(TAG, "microphone silent for ${stallMs}ms, restarting capture")
                    if (restartCapture()) {
                        attempts++
                    } else {
                        running = false
                        break
                    }
                    stallMs = 0
                }
            }
        }
        record?.let { rec ->
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            try {
                rec.release()
            } catch (_: Exception) {
            }
        }
        record = null
    }

    /** Tears the capture down and opens it again; false when that fails. */
    private fun restartCapture(): Boolean {
        val old = record
        record = null
        old?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        val fresh = if (minBuf > 0) openRecorder(minBuf * 4) else null
        if (fresh == null || !begin(fresh)) {
            fresh?.release()
            Log.w(TAG, "capture restart failed")
            return false
        }
        record = fresh
        Log.i(TAG, "capture restarted")
        return true
    }

    fun stop() {
        running = false
        worker?.join(800)
        worker = null
        record?.let {
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        record = null
        Log.i(TAG, "capture stopped")
    }

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "OpenCam/Audio"
        private const val POLL_MS = 10L
        private const val STALL_RESTART_MS = 3_000L

        /** Tried in order; the first one that initialises is used. */
        private val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT
        )
    }
}
