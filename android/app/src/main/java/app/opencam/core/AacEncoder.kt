package app.opencam.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Encodes 16-bit PCM to AAC-LC and wraps every access unit in an ADTS header so
 * a client can decode the stream without container negotiation. This is the
 * format the reference server uses on /v2/audio and /v4/audio.
 */
class AacEncoder(
    private val sampleRate: Int = StreamHub.AUDIO_SAMPLE_RATE,
    private val channels: Int = StreamHub.AUDIO_CHANNELS,
    private val bitrate: Int = 128_000
) {

    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private var running = false

    fun start() {
        if (running) return
        try {
            val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec = MediaCodec.createEncoderByType(MIME).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
            running = true
        } catch (e: Exception) {
            Log.w(TAG, "AAC encoder unavailable", e)
            codec = null
            running = false
        }
    }

    val isRunning: Boolean get() = running

    /**
     * Feeds one PCM block and appends each produced AAC access unit to [out].
     * With [adts] each unit gets a seven-byte ADTS header; without it the units
     * are raw, which is what the stock client expects after the config packet.
     */
    fun encode(pcm: ByteArray, length: Int, out: MutableList<ByteArray>, adts: Boolean = true) {
        val c = codec ?: return
        if (!running) return
        try {
            val inIndex = c.dequeueInputBuffer(0)
            if (inIndex >= 0) {
                val buf = c.getInputBuffer(inIndex)
                if (buf != null) {
                    buf.clear()
                    buf.put(pcm, 0, length)
                    c.queueInputBuffer(inIndex, 0, length, System.nanoTime() / 1000, 0)
                }
            }

            while (true) {
                val outIndex = c.dequeueOutputBuffer(info, 0)
                if (outIndex < 0) break
                val buf = c.getOutputBuffer(outIndex)
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (buf != null && info.size > 0 && !isConfig) {
                    val aac = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(aac)
                    if (!adts) {
                        out.add(aac)
                    } else {
                        val header = adtsHeader(aac.size)
                        val framed = ByteArray(header.size + aac.size)
                        System.arraycopy(header, 0, framed, 0, header.size)
                        System.arraycopy(aac, 0, framed, header.size, aac.size)
                        out.add(framed)
                    }
                }
                c.releaseOutputBuffer(outIndex, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "encode failed", e)
        }
    }

    fun stop() {
        running = false
        val c = codec ?: return
        codec = null
        try {
            c.stop()
        } catch (_: Exception) {
        }
        try {
            c.release()
        } catch (_: Exception) {
        }
    }

    /**
     * The MPEG-4 AudioSpecificConfig matching the running encoder: object type 2
     * (AAC LC), the sample rate index and the channel configuration. The stock
     * client takes this as its config packet and then decodes raw access units.
     * Two bytes for the rates and channel counts OpenCam uses.
     */
    fun audioSpecificConfig(): ByteArray {
        val freqIndex = sampleRateIndex()
        val objectType = 2
        val channelConfig = channels
        val first = ((objectType and 0x1F) shl 3) or ((freqIndex shr 1) and 0x07)
        val second = ((freqIndex and 0x01) shl 7) or ((channelConfig and 0x0F) shl 3)
        return byteArrayOf(first.toByte(), second.toByte())
    }

    private fun sampleRateIndex(): Int = when (sampleRate) {
        96000 -> 0
        88200 -> 1
        64000 -> 2
        48000 -> 3
        44100 -> 4
        32000 -> 5
        24000 -> 6
        22050 -> 7
        16000 -> 8
        12000 -> 9
        11025 -> 10
        8000 -> 11
        else -> 3
    }

    /** Seven-byte ADTS header for a payload of [payloadLength] bytes. */
    private fun adtsHeader(payloadLength: Int): ByteArray {
        val frameLength = payloadLength + 7
        val freqIndex = sampleRateIndex()
        val objectType = 2 // AAC LC -> ADTS profile value is objectType - 1
        val channelConfig = channels
        val h = ByteArray(7)
        h[0] = 0xFF.toByte()
        h[1] = 0xF1.toByte() // MPEG-4, layer 0, no CRC
        h[2] = (((objectType - 1) shl 6) or (freqIndex shl 2) or
            ((channelConfig shr 2) and 0x01)).toByte()
        h[3] = ((((channelConfig and 0x03)) shl 6) or ((frameLength shr 11) and 0x03)).toByte()
        h[4] = ((frameLength shr 3) and 0xFF).toByte()
        h[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        h[6] = 0xFC.toByte()
        return h
    }

    private companion object {
        const val MIME = "audio/mp4a-latm"
        const val TAG = "OpenCam/Aac"
    }
}
