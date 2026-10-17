package app.opencam.core

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Fan-out point between the capture engines and the connected clients.
 *
 * Video uses a single-slot mailbox per consumer: when a client cannot keep up the
 * older frame is dropped instead of stalling the camera. Audio uses a short
 * bounded queue so playback stays continuous without unbounded memory growth.
 */
class StreamHub {

    class Frame(val jpeg: ByteArray, val width: Int, val height: Int)

    /** One encoded video access unit, as the stock client's avc mode wants them. */
    class EncodedUnit(val data: ByteArray, val keyframe: Boolean, val ptsUs: Long)

    /** One connected video client. */
    inner class VideoConsumer {
        private val slot = ArrayBlockingQueue<Frame>(1)
        val dropped = AtomicLong()
        @Volatile var closed = false

        fun offer(frame: Frame) {
            if (closed) return
            if (!slot.offer(frame)) {
                slot.poll()
                if (slot.offer(frame)) dropped.incrementAndGet() else dropped.incrementAndGet()
            }
        }

        /** Blocks up to [timeoutMs] for the next frame, or returns null. */
        fun next(timeoutMs: Long): Frame? =
            try {
                slot.poll(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }

        fun close() {
            closed = true
            slot.clear()
        }
    }

    /** One connected encoded-video client (the stock client's avc format). */
    inner class EncodedConsumer {
        private val slot = ArrayBlockingQueue<EncodedUnit>(1)
        val dropped = AtomicLong()
        @Volatile var closed = false

        fun offer(unit: EncodedUnit) {
            if (closed) return
            if (!slot.offer(unit)) {
                slot.poll()
                slot.offer(unit)
                dropped.incrementAndGet()
            }
        }

        /** Blocks up to [timeoutMs] for the next unit, or returns null. */
        fun next(timeoutMs: Long): EncodedUnit? =
            try {
                slot.poll(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }

        fun close() {
            closed = true
            slot.clear()
        }
    }

    /** One connected audio client. */
    inner class AudioConsumer(
        val sampleRate: Int,
        val channels: Int,
        val bytesPerSample: Int
    ) {
        private val queue = ArrayBlockingQueue<ByteArray>(64)
        val dropped = AtomicLong()
        @Volatile var closed = false

        val byteRate: Int get() = sampleRate * channels * bytesPerSample

        fun offer(data: ByteArray) {
            if (closed) return
            if (!queue.offer(data)) {
                queue.poll()
                queue.offer(data)
                dropped.incrementAndGet()
            }
        }

        fun next(timeoutMs: Long): ByteArray? =
            try {
                queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }

        fun close() {
            closed = true
            queue.clear()
        }
    }

    private val videoConsumers = CopyOnWriteArrayList<VideoConsumer>()
    private val audioConsumers = CopyOnWriteArrayList<AudioConsumer>()
    private val encodedConsumers = CopyOnWriteArrayList<EncodedConsumer>()

    /**
     * Decoder configuration the stock client needs before encoded frames: the
     * SPS+PPS pair in annex-B form. Published when the H.264 encoder reports its
     * output format and re-sent as a config packet to every new client.
     */
    @Volatile var avcConfig: ByteArray? = null
        private set

    @Volatile var lastFrame: Frame? = null
        private set

    @Volatile var lastFrameAt = 0L
        private set

    val videoClientCount: Int get() = videoConsumers.count { !it.closed }
    val audioClientCount: Int get() = audioConsumers.count { !it.closed }
    val encodedClientCount: Int get() = encodedConsumers.count { !it.closed }

    fun addVideoConsumer(): VideoConsumer = VideoConsumer().also { videoConsumers.add(it) }
    fun addAudioConsumer(sampleRate: Int, channels: Int, bytesPerSample: Int): AudioConsumer =
        AudioConsumer(sampleRate, channels, bytesPerSample).also { audioConsumers.add(it) }
    fun addEncodedConsumer(): EncodedConsumer = EncodedConsumer().also { encodedConsumers.add(it) }

    fun removeVideoConsumer(c: VideoConsumer) {
        c.close()
        videoConsumers.remove(c)
    }

    fun removeAudioConsumer(c: AudioConsumer) {
        c.close()
        audioConsumers.remove(c)
    }

    fun removeEncodedConsumer(c: EncodedConsumer) {
        c.close()
        encodedConsumers.remove(c)
    }

    /** Called from the camera thread for every encoded frame. */
    fun publishVideo(jpeg: ByteArray, width: Int, height: Int) {
        val frame = Frame(jpeg, width, height)
        lastFrame = frame
        lastFrameAt = System.currentTimeMillis()
        for (c in videoConsumers) c.offer(frame)
    }

    /** Called from the audio thread for every PCM block. A copy is enqueued per client. */
    fun publishAudio(pcm: ByteArray, length: Int) {
        if (audioConsumers.isEmpty()) return
        for (c in audioConsumers) {
            val copy = ByteArray(length)
            System.arraycopy(pcm, 0, copy, 0, length)
            c.offer(copy)
        }
    }

    /** Called from the H.264 encoder for every access unit. */
    fun publishEncoded(data: ByteArray, keyframe: Boolean, ptsUs: Long) {
        if (encodedConsumers.isEmpty()) return
        val unit = EncodedUnit(data, keyframe, ptsUs)
        for (c in encodedConsumers) c.offer(unit)
    }

    fun publishAvcConfig(csd: ByteArray) {
        avcConfig = csd
    }

    fun hasVideoClients(): Boolean = videoConsumers.any { !it.closed }

    fun closeAll() {
        for (c in videoConsumers) c.close()
        for (c in audioConsumers) c.close()
        for (c in encodedConsumers) c.close()
        videoConsumers.clear()
        audioConsumers.clear()
        encodedConsumers.clear()
    }

    companion object {
        const val AUDIO_SAMPLE_RATE = 48000
        const val AUDIO_CHANNELS = 1
        const val AUDIO_BYTES_PER_SAMPLE = 2
        const val AUDIO_BITS_PER_SAMPLE = 16
    }
}
