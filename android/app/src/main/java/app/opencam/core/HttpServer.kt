package app.opencam.core

import android.util.Log
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The OpenCam streaming server.
 *
 * The route surface mirrors the reference Android server, so a stock DroidCam
 * desktop client can drive OpenCam, and the OpenCam desktop client can drive a
 * stock DroidCam phone. Two client dialects share the same routes:
 *
 *  * A proper HTTP request (the OpenCam desktop client, a browser) gets HTTP
 *    responses: `/v5/video/...` streams multipart MJPEG, `/v1/audio` streams
 *    WAV and `/v2/audio` streams ADTS AAC.
 *
 *  * A bare request line with no HTTP version - what the stock client sends on
 *    `/v5/video/<format>/...` and on `/v2/audio` - gets the binary framing the
 *    reference phone uses: a 12-byte header (8-byte big-endian packet id and a
 *    4-byte big-endian payload length) right before the payload, and no HTTP
 *    response at all. A packet whose id is all ones carries a decoder
 *    configuration blob (AAC AudioSpecificConfig, or SPS+PPS for H.264) which
 *    the client prepends to every following frame.
 *
 *  * Formats on the stock route: `jpg`/`jpeg`/`mjpg`/`mjpeg` send JPEG frames
 *    as plain packets; `avc`/`h264` send an SPS+PPS config packet and then
 *    annex-B access units. The stock client names the format in the path. An
 *    OpenCam client uses a camera token (`back`, `front`, a number) instead and
 *    gets multipart MJPEG, and PUT requests steer the camera as usual.
 */
class HttpServer(private val hub: StreamHub, private val control: ControlSurface) {

    private val clients = CopyOnWriteArrayList<Client>()
    private var acceptThread: Thread? = null
    private var serverSocket: ServerSocket? = null

    @Volatile private var running = false

    @Volatile var lastError: String? = null
        private set

    val isRunning: Boolean get() = running

    val clientCount: Int get() = clients.count { !it.finished }

    fun start(port: Int): Boolean {
        if (running) return true
        lastError = null
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            serverSocket = ss
            running = true
            acceptThread = Thread({ acceptLoop(ss) }, "opencam-http").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "listening on $port")
            return true
        } catch (e: Exception) {
            lastError = e.message ?: "bind failed"
            Log.e(TAG, "start on $port failed", e)
            running = false
            return false
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        for (c in clients.toList()) c.close()
        clients.clear()
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val socket = ss.accept()
                val client = Client(socket)
                clients.add(client)
                Thread({ client.run() }, "opencam-client").apply {
                    isDaemon = true
                    start()
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept ended: ${e.message}")
                return
            }
        }
    }

    // ------------------------------------------------------------------ clients

    private inner class Client(val socket: Socket) {
        @Volatile var finished = false
        @Volatile var videoConsumer: StreamHub.VideoConsumer? = null
        @Volatile var audioConsumer: StreamHub.AudioConsumer? = null
        @Volatile var encodedConsumer: StreamHub.EncodedConsumer? = null

        fun run() {
            try {
                socket.tcpNoDelay = true
                val input = socket.getInputStream()
                val output = BufferedOutputStream(socket.getOutputStream(), 65536)
                val request = try {
                    readRequest(socket, input)
                } catch (e: SocketException) {
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "client error: ${e.message}")
                    null
                }
                if (request != null) {
                    Log.i(TAG, "${socket.inetAddress.hostAddress} ${request.method} ${request.target}")
                    route(this, request, output)
                }
            } finally {
                close()
            }
        }

        fun close() {
            if (finished) return
            finished = true
            videoConsumer?.let { hub.removeVideoConsumer(it) }
            audioConsumer?.let { hub.removeAudioConsumer(it) }
            encodedConsumer?.let { hub.removeEncodedConsumer(it) }
            videoConsumer = null
            audioConsumer = null
            encodedConsumer = null
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private class Request(
        val method: String,
        val target: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
        /**
         * True when the request line carried no HTTP version, which is how the
         * stock client asks: `GET /v5/video/jpg/.../nonce/1/` with no CRLF and
         * no headers. Those routes answer with binary framing instead of HTTP.
         */
        val bare: Boolean
    )

    /**
     * Reads one request. A stock-client request line has no terminator at all,
     * so the head ends at the first idle moment: the wait for the first bytes is
     * generous, and once bytes are flowing a short timeout decides that the
     * request is complete. A normal HTTP request ends at CRLFCRLF instead.
     */
    private fun readRequest(socket: Socket, input: InputStream): Request? {
        val raw = readHeadBytes(socket, input) ?: return null
        val headEnd = indexOfCrlfCrlf(raw)
        val headBytes = if (headEnd >= 0) raw.copyOfRange(0, headEnd) else raw
        val leftover = if (headEnd >= 0) raw.copyOfRange(headEnd + 4, raw.size) else ByteArray(0)
        val head = String(headBytes, Charsets.ISO_8859_1)

        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val parts = lines[0].split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.ROOT)
        val target = parts[1]
        val bare = parts.size < 3

        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase(Locale.ROOT)] = line.substring(idx + 1).trim()
            }
        }

        val qIdx = target.indexOf('?')
        val path = if (qIdx >= 0) target.substring(0, qIdx) else target
        val query = if (qIdx >= 0) parseQuery(target.substring(qIdx + 1)) else emptyMap()

        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len in 1..65536) {
            val buf = ByteArray(len)
            var read = 0
            if (leftover.isNotEmpty()) {
                val copy = minOf(leftover.size, len)
                System.arraycopy(leftover, 0, buf, 0, copy)
                read = copy
            }
            while (read < len) {
                val n = input.read(buf, read, len - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        } else {
            ""
        }
        return Request(method, target, path, query, headers, body, bare)
    }

    private fun readHeadBytes(socket: Socket, input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream(512)
        val chunk = ByteArray(512)
        socket.soTimeout = HEAD_WAIT_MS
        while (out.size() < 16384) {
            val n = try {
                input.read(chunk)
            } catch (e: SocketTimeoutException) {
                // Nothing more is coming: with the stock client's bare request
                // that is the normal end of the head.
                break
            }
            if (n < 0) break
            if (n == 0) continue
            out.write(chunk, 0, n)
            if (indexOfCrlfCrlf(out.toByteArray()) >= 0) break
            socket.soTimeout = HEAD_IDLE_MS
        }
        return if (out.size() == 0) null else out.toByteArray()
    }

    private fun indexOfCrlfCrlf(data: ByteArray): Int {
        for (i in 0 until data.size - 3) {
            if (data[i] == '\r'.code.toByte() && data[i + 1] == '\n'.code.toByte() &&
                data[i + 2] == '\r'.code.toByte() && data[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) map[pair] = "" else map[pair.substring(0, eq)] = pair.substring(eq + 1)
        }
        return map
    }

    // ------------------------------------------------------------------- routes

    private fun route(client: Client, r: Request, out: BufferedOutputStream) {
        val path = r.path
        if (r.method == "GET") {
            if (path == "/" || path == "/index") {
                writeStatus(out, "302 Found", listOf("Location: /remote"))
                return
            }
            if (path == "/remote" || path == "/remote.htm" || path == "/remote.html") {
                writeHtml(out, remotePage())
                return
            }
            if (path == "/assets/strings.js") {
                writeText(out, stringsJs(), "application/javascript")
                return
            }
            if (isVideoRequest(path)) {
                if (r.bare) {
                    // The stock client names the wire format after "video".
                    when (formatFromToken(videoToken(path))) {
                        "jpg" -> streamFramedJpeg(client, out, r.path)
                        "avc" -> streamFramedAvc(client, out, r.path)
                    }
                } else {
                    streamVideo(client, out, r)
                }
                return
            }
            if (path == "/v1/audio") {
                streamWav(client, out)
                return
            }
            if (path == "/v2/audio" || path == "/v4/audio" || path == "/v1/audio.2") {
                if (r.bare && path == "/v2/audio") streamFramedAac(client, out) else streamAac(client, out)
                return
            }
            if (path == "/battery" || path == "/v1/phone/battery_level") {
                writeText(out, control.batteryLevel().toString())
                return
            }
            if (path == "/v1/phone/battery_info") {
                val charging = if (control.isCharging()) "Charging" else "Discharging"
                writeText(out, "${control.batteryLevel()}% $charging")
                return
            }
            if (path == "/v1/phone/name") {
                writeText(out, control.deviceName())
                return
            }
            if (path == "/v1/camera/camera_list") {
                writeText(out, control.cameraList().joinToString("\n"))
                return
            }
            if (path == "/v1/camera/info") {
                writeText(out, cameraInfo())
                return
            }
            if (path == "/v1/camera/limits") {
                writeJson(out, limitsJson())
                return
            }
            if (path == "/v1/status") {
                writeJson(out, statusJson())
                return
            }
            if (path == "/v1/camera/tally") {
                writeText(out, control.tallyState().toString())
                return
            }
        }
        if ((r.method == "PUT" || r.method == "POST") && handleControl(r, out)) return
        writeStatus(out, "404 Not Found", listOf("Content-Type: text/plain"))
        out.write("No matches for request: ${r.target}".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun isVideoRequest(path: String): Boolean {
        if (path == "/video" || path == "/videofeed" || path == "/mjpegfeed") return true
        return VIDEO_ROOTS.any { path == it || path.startsWith("$it/") }
    }

    /**
     * The token following the "video" segment of a video route. On the stock
     * client this is a format name; on OpenCam routes it is the camera to use.
     * Null when the route carries no token.
     */
    private fun videoToken(path: String): String? {
        val segments = path.trim('/').split('/')
        for ((i, s) in segments.withIndex()) {
            if (s.length >= 2 && (s[0] == 'v' || s[0] == 'V') && s.drop(1).toIntOrNull() != null) {
                val at = if (segments.getOrNull(i + 1)?.equals("video", true) == true) i + 2 else i + 1
                return segments.getOrNull(at)
            }
        }
        val at = segments.indexOfFirst {
            it.equals("video", true) || it.equals("videofeed", true) || it.equals("mjpegfeed", true)
        }
        return if (at >= 0) segments.getOrNull(at + 1) else null
    }

    /** "jpg"-style names as the stock client writes them, normalised. */
    private fun formatFromToken(token: String?): String? =
        when (token?.lowercase(Locale.ROOT)) {
            "avc", "h264" -> "avc"
            "jpg", "jpeg", "mjpg", "mjpeg" -> "jpg"
            else -> null
        }

    /** "back" / "front" / a camera number, as OpenCam routes write them. */
    private fun parseCameraToken(token: String?): Int? = when {
        token == null -> null
        token.equals("back", true) -> 0
        token.equals("front", true) -> 1
        else -> token.toIntOrNull()?.takeIf { it >= 0 }
    }

    // ------------------------------------------------------------ multipart video

    /**
     * Applies the size a client asked for in its path, e.g.
     * `/v5/video/avc/1280x720/...`, when it differs from the live setting.
     * True when that restarted the capture pipeline.
     */
    private fun applySizeHint(path: String): Boolean {
        val size = parseSizeFromPath(path) ?: return false
        val (w, h) = size
        if (w == control.settings().width && h == control.settings().height) return false
        control.requestSize(w, h)
        return true
    }

    private fun streamVideo(client: Client, out: BufferedOutputStream, r: Request) {
        applySizeHint(r.path)
        parseCameraToken(videoToken(r.path))?.let { index ->
            if (index != control.activeCameraIndex()) control.switchCamera(index)
        }
        val consumer = hub.addVideoConsumer()
        client.videoConsumer = consumer
        try {
            val header = "HTTP/1.0 200 OK\r\n" +
                "Server: OpenCam\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=--BoundaryString\r\n" +
                "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.flush()
            var frame = hub.lastFrame
            val deadline = System.currentTimeMillis() + 3000
            while (frame == null && System.currentTimeMillis() < deadline && running) {
                Thread.sleep(20L)
                frame = hub.lastFrame
            }
            if (frame != null) writeFrame(out, frame)
            while (running && !client.finished) {
                val next = consumer.next(1000L)
                if (next == null) {
                    if (peerGone(client)) break
                    continue
                }
                writeFrame(out, next)
            }
        } catch (_: Exception) {
        } finally {
            client.videoConsumer = null
            hub.removeVideoConsumer(consumer)
        }
    }

    private fun writeFrame(out: BufferedOutputStream, frame: StreamHub.Frame) {
        val head = StringBuilder()
        head.append("----BoundaryString\r\n")
        head.append("Content-Type: image/jpeg\r\n")
        head.append("Content-Length: " + frame.jpeg.size + "\r\n")
        head.append("X-Timestamp: " + (System.currentTimeMillis() / 1000.0) + "\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(frame.jpeg)
        out.write(CRLF)
        out.flush()
    }

    // ------------------------------------------------------------- binary framing

    /**
     * One packet of the stock client's framing: an 8-byte big-endian id and a
     * 4-byte big-endian payload length, then the payload.
     */
    private fun writePacket(out: BufferedOutputStream, tag: Long, payload: ByteArray, flush: Boolean = true) {
        val head = ByteArray(12)
        var v = tag
        for (i in 7 downTo 0) {
            head[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        var n = payload.size
        for (i in 11 downTo 8) {
            head[i] = (n and 0xFF).toByte()
            n = n ushr 8
        }
        out.write(head)
        out.write(payload)
        if (flush) out.flush()
    }

    private fun writeConfigPacket(out: BufferedOutputStream, payload: ByteArray) {
        writePacket(out, CONFIG_TAG, payload)
    }

    /**
     * True when the peer has closed its end. A one-millisecond peek at the
     * socket: EOF means the client went away, a timeout means it is merely
     * quiet. Streams that have nothing to send for a while (a stalled encoder,
     * a dead microphone) still notice a client that has given up instead of
     * leaving its thread, consumer and codec behind.
     */
    private fun peerGone(client: Client): Boolean {
        return try {
            client.socket.soTimeout = 1
            try {
                client.socket.getInputStream().read() < 0
            } finally {
                try {
                    client.socket.soTimeout = 0
                } catch (_: Exception) {
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            false
        } catch (_: Exception) {
            true
        }
    }

    /**
     * JPEG frames as plain packets, one per frame, with the u64 field carrying
     * the frame's microsecond presentation time. The path carries the size the
     * client wants, the same way the multipart route does.
     */
    private fun streamFramedJpeg(client: Client, out: BufferedOutputStream, path: String) {
        applySizeHint(path)
        val consumer = hub.addVideoConsumer()
        client.videoConsumer = consumer
        try {
            // The stock client reads the id as a presentation time in
            // microseconds; the first frame of a connection starts at zero.
            var firstAtNs = -1L
            var frame = hub.lastFrame
            val deadline = System.currentTimeMillis() + 3000
            while (frame == null && System.currentTimeMillis() < deadline && running) {
                Thread.sleep(20L)
                frame = hub.lastFrame
            }
            if (frame != null) {
                firstAtNs = System.nanoTime()
                writePacket(out, 0L, frame.jpeg)
            }
            while (running && !client.finished) {
                val next = consumer.next(1000L)
                if (next == null) {
                    if (peerGone(client)) break
                    continue
                }
                if (firstAtNs < 0) firstAtNs = System.nanoTime()
                val tag = (System.nanoTime() - firstAtNs) / 1000L
                writePacket(out, tag, next.jpeg)
            }
        } catch (_: Exception) {
        } finally {
            client.videoConsumer = null
            hub.removeVideoConsumer(consumer)
        }
    }

    /**
     * The SPS+PPS config packet, then annex-B access units. Frames are skipped
     * until the first key frame so a client that connects mid-GOP never sees a
     * delta frame it cannot decode. The path carries the size the client wants;
     * when that forces capture to restart, the config that is still published
     * belongs to the previous encoder, and the stock client rejects a second
     * config on one connection, so the fresh one is waited for instead.
     */
    private fun streamFramedAvc(client: Client, out: BufferedOutputStream, path: String) {
        val stale = hub.avcConfig
        val restarted = applySizeHint(path)
        val consumer = hub.addEncodedConsumer()
        client.encodedConsumer = consumer
        var sent = 0L
        try {
            var config = hub.avcConfig
            val deadline = System.currentTimeMillis() + 3000
            while (
                ((config == null) || (restarted && config === stale)) &&
                System.currentTimeMillis() < deadline && running && !client.finished
            ) {
                Thread.sleep(20L)
                config = hub.avcConfig
            }
            if (config == null) {
                Log.i(TAG, "avc stream: no config after 3s, closing")
                return
            }
            writeConfigPacket(out, config)
            Log.i(TAG, "avc stream: config ${config.size}, waiting for key frame")
            var basePtsUs = -1L
            var started = false
            var skipped = 0L
            var seen: ByteArray = config
            while (running && !client.finished) {
                val unit = consumer.next(1000L)
                if (unit == null) {
                    if (peerGone(client)) break
                    continue
                }
                // Capture restarted (size or camera change), so this decoder
                // setup is stale and the client cannot take a second config
                // mid-stream. End the connection; clients reconnect at once and
                // get the new configuration as the first packet.
                val current = hub.avcConfig
                if (current != null && current !== seen) {
                    Log.i(TAG, "avc stream: capture restarted, closing for reconnect")
                    break
                }
                if (!started) {
                    if (!unit.keyframe) {
                        skipped++
                        continue
                    }
                    started = true
                    basePtsUs = unit.ptsUs
                    Log.i(TAG, "avc stream: first key frame after $skipped skipped")
                }
                val tag = (unit.ptsUs - basePtsUs).coerceAtLeast(0L)
                writePacket(out, tag, unit.data)
                sent++
            }
        } catch (_: Exception) {
        } finally {
            Log.i(TAG, "avc stream: ended, sent=$sent")
            client.encodedConsumer = null
            hub.removeEncodedConsumer(consumer)
        }
    }

    /**
     * The AudioSpecificConfig packet, then raw AAC access units. The stock
     * client configures its decoder from the first packet and takes everything
     * after it as raw AAC, so the ADTS wrapping is left off here.
     */
    private fun streamFramedAac(client: Client, out: BufferedOutputStream) {
        val consumer = hub.addAudioConsumer(48000, 1, 2)
        val encoder = AacEncoder()
        client.audioConsumer = consumer
        try {
            // The config is independent of the encoder, so a client always gets
            // its decoder set up even if the codec takes a moment to come up.
            writeConfigPacket(out, encoder.audioSpecificConfig())
            encoder.start()
            // Media packets carry a microsecond presentation time. AAC-LC emits
            // 1024 samples per access unit, so the clock advances per unit.
            var samples = 0L
            val produced = ArrayList<ByteArray>(8)
            while (running && !client.finished) {
                val block = consumer.next(1000L)
                if (block == null) {
                    if (peerGone(client)) break
                    continue
                }
                produced.clear()
                encoder.encode(block, block.size, produced, adts = false)
                for (frame in produced) {
                    writePacket(out, samples * 1_000_000L / AUDIO_RATE, frame, flush = false)
                    samples += AAC_FRAME_SAMPLES
                }
                if (produced.isNotEmpty()) out.flush()
            }
        } catch (_: Exception) {
        } finally {
            encoder.stop()
            client.audioConsumer = null
            hub.removeAudioConsumer(consumer)
        }
    }

    // -------------------------------------------------------------------- audio

    private fun streamWav(client: Client, out: BufferedOutputStream) {
        val consumer = hub.addAudioConsumer(48000, 1, 2)
        client.audioConsumer = consumer
        try {
            val header = "HTTP/1.0 200 OK\r\n" +
                "Server: OpenCam\r\n" +
                "Content-Type: audio/wav\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(wavHeader())
            out.flush()
            while (running && !client.finished) {
                val block = consumer.next(1000L)
                if (block == null) {
                    if (peerGone(client)) break
                    continue
                }
                out.write(block)
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            client.audioConsumer = null
            hub.removeAudioConsumer(consumer)
        }
    }

    private fun streamAac(client: Client, out: BufferedOutputStream) {
        val consumer = hub.addAudioConsumer(48000, 1, 2)
        val encoder = AacEncoder()
        encoder.start()
        client.audioConsumer = consumer
        try {
            val header = "HTTP/1.0 200 OK\r\n" +
                "Server: OpenCam\r\n" +
                "Content-Type: audio/aac\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.flush()
            val produced = ArrayList<ByteArray>(8)
            while (running && !client.finished) {
                val block = consumer.next(1000L)
                if (block == null) {
                    if (peerGone(client)) break
                    continue
                }
                produced.clear()
                encoder.encode(block, block.size, produced)
                for (frame in produced) out.write(frame)
                if (produced.isNotEmpty()) out.flush()
            }
        } catch (_: Exception) {
        } finally {
            encoder.stop()
            client.audioConsumer = null
            hub.removeAudioConsumer(consumer)
        }
    }

    // ---------------------------------------------------------------- responses

    private fun writeStatus(out: BufferedOutputStream, status: String, extra: List<String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.0 ").append(status).append("\r\n")
        sb.append("Server: OpenCam\r\n")
        for (e in extra) sb.append(e).append("\r\n")
        sb.append("Content-Length: 0\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun writeText(out: BufferedOutputStream, text: String, contentType: String = "text/plain") {
        val body = text.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.0 200 OK\r\n")
        sb.append("Server: OpenCam\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun writeJson(out: BufferedOutputStream, json: String) {
        writeText(out, json, "application/json")
    }

    private fun writeHtml(out: BufferedOutputStream, html: String) {
        writeText(out, html, "text/html; charset=utf-8")
    }

    private fun cameraInfo(): String {
        val s = control.settings()
        val idx = control.activeCameraIndex()
        val list = control.cameraList()
        val sb = StringBuilder()
        sb.append("camera=").append(if (idx in list.indices) list[idx] else "?")
        sb.append(" active=").append(idx)
        sb.append(" size=").append(s.width).append('x').append(s.height)
        sb.append(" fps=").append(s.fps)
        sb.append(" zoom=").append(s.zoom)
        sb.append(" ev=").append(s.ev)
        sb.append(" iso=").append(s.iso)
        sb.append(" ss_ns=").append(s.exposureNs)
        sb.append(" wb=").append(s.wbModeIndex)
        sb.append(" wb_level=").append(s.wbLevel)
        sb.append(" af=").append(s.afModeIndex)
        sb.append(" mf=").append(s.manualFocus)
        sb.append(" torch=").append(s.torch)
        sb.append(" mic=").append(s.micEnabled)
        return sb.toString()
    }

    private fun limitsJson(): String {
        val s = control.settings()
        return """
        {
          "port": ${control.port()},
          "cameras": ${control.cameraList().size},
          "active": ${control.activeCameraIndex()},
          "width": ${s.width},
          "height": ${s.height},
          "fps": ${s.fps},
          "audio_sample_rate": 48000,
          "audio_channels": 1,
          "wb_modes": ${CameraSettings.WB_LABELS.size},
          "af_modes": ${CameraSettings.AF_LABELS.size}
        }
        """.trimIndent()
    }

    private fun statusJson(): String {
        val s = control.settings()
        return """
        {
          "name": "${jsonEscape(control.deviceName())}",
          "port": ${control.port()},
          "streaming": ${hub.lastFrame != null},
          "video_clients": ${hub.videoClientCount + hub.encodedClientCount},
          "audio_clients": ${hub.audioClientCount},
          "fps": ${control.settings().fps},
          "width": ${s.width},
          "height": ${s.height},
          "battery": ${control.batteryLevel()},
          "tally": ${control.tallyState()}
        }
        """.trimIndent()
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun stringsJs(): String =
        "var OC_STRINGS = {\n  app: \"OpenCam\",\n  start: \"Start\",\n  stop: \"Stop\",\n  " +
            "streaming: \"Streaming\",\n  idle: \"Idle\",\n  clients: \"Clients\",\n  " +
            "camera: \"Camera\",\n  resolution: \"Resolution\",\n  battery: \"Battery\"\n};"

    private fun remotePage(): String = """
<!doctype html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>OpenCam</title>
<style>
  body { background:#1b1b1b; color:#fdfdfd; font-family:sans-serif; margin:0; padding:24px; }
  h1 { font-size:20px; margin:0 0 4px; }
  .muted { color:#b3ffffff; font-size:13px; }
  .row { display:flex; flex-wrap:wrap; gap:8px; margin-top:16px; }
  button { background:#2a2a2a; color:#fdfdfd; border:1px solid #444; border-radius:6px;
           padding:10px 14px; font-size:14px; cursor:pointer; }
  button:active { background:#3a3a3a; }
  .accent { color:#fb1a67; }
  .val { color:#fb1a67; }
  .grid { margin-top:16px; display:grid; grid-template-columns:repeat(auto-fit,minmax(220px,1fr)); gap:16px; }
  .card { background:#202020; border:1px solid #333; border-radius:8px; padding:12px; }
  input[type=range] { width:100%; }
</style>
</head><body>
<h1>OpenCam <span class="accent">remote</span></h1>
<div class="muted" id="status">loading...</div>
<div class="row">
  <button onclick="put('/v1/camera/mic_toggle')">Mic</button>
  <button onclick="put('/v1/camera/torch_toggle')">Torch</button>
  <button onclick="put('/v1/camera/autofocus')">Autofocus</button>
  <button onclick="put('/v1/camera/ae_toggle')">AE lock</button>
  <button onclick="put('/v1/camera/wbl_toggle')">WB lock</button>
  <button onclick="put('/v1/restart')">Restart</button>
  <button onclick="put('/v1/stop')">Stop</button>
</div>
<div class="grid">
  <div class="card"><div class="muted">Zoom</div>
    <input id="zoom" type="range" min="0" max="1000" value="0">
    <div class="val" id="zoomVal">0.00</div></div>
  <div class="card"><div class="muted">Exposure compensation</div>
    <input id="ev" type="range" min="-32" max="32" value="0">
    <div class="val" id="evVal">0</div></div>
  <div class="card"><div class="muted">ISO (0 = auto)</div>
    <input id="iso" type="range" min="0" max="6400" step="50" value="0">
    <div class="val" id="isoVal">0</div></div>
  <div class="card"><div class="muted">Shutter ms (0 = auto)</div>
    <input id="ss" type="range" min="0" max="500" value="0">
    <div class="val" id="ssVal">0</div></div>
  <div class="card"><div class="muted">Manual focus</div>
    <input id="mf" type="range" min="0" max="1000" value="0">
    <div class="val" id="mfVal">0.00</div></div>
  <div class="card"><div class="muted">White balance level</div>
    <input id="wb" type="range" min="0" max="1000" value="500">
    <div class="val" id="wbVal">0.50</div></div>
</div>
<script>
  function put(path, params) {
    var url = path + (params ? '?' + params : '');
    fetch(url, { method: 'PUT' }).then(refresh).catch(function(){});
  }
  function refresh() {
    fetch('/v1/status').then(function(r){ return r.json(); }).then(function(s){
      document.getElementById('status').innerHTML =
        (s.streaming ? 'Streaming' : 'Idle') + ' &middot; ' +
        s.width + 'x' + s.height + ' @ ' + s.fps + 'fps &middot; ' +
        s.video_clients + ' video / ' + s.audio_clients + ' audio client(s) &middot; ' +
        'battery ' + s.battery + '%';
    }).catch(function(){});
  }
  function bind(id, path, scale) {
    var el = document.getElementById(id);
    var label = document.getElementById(id + 'Val');
    el.addEventListener('input', function() {
      var v = el.value;
      label.textContent = scale ? (v / scale).toFixed(2) : v;
    });
    el.addEventListener('change', function() {
      var v = el.value;
      put('/v3/camera/' + path + '/' + (scale ? (v / scale) : v));
    });
  }
  bind('zoom', 'zoom', 1000);
  bind('ev', 'ev', 10);
  bind('iso', 'iso', 0);
  bind('ss', 'ss', 0);
  bind('mf', 'mf', 1000);
  document.getElementById('wb').addEventListener('change', function() {
    var v = this.value / 1000;
    document.getElementById('wbVal').textContent = v.toFixed(2);
    put('/v2/camera/wb_level', 'wb_level=' + v);
  });
  refresh();
  setInterval(refresh, 3000);
</script>
</body></html>
""".trimStart('\n').trimEnd('\n')

    private fun wavHeader(): ByteArray {
        val byteRate = (48000 * 1 * 16) / 8
        val blockAlign = (1 * 16) / 8
        val h = ByteArray(44)
        putAscii(h, 0, "RIFF")
        putInt32(h, 4, 4294967295L)
        putAscii(h, 8, "WAVE")
        putAscii(h, 12, "fmt ")
        putInt32(h, 16, 16L)
        putInt16(h, 20, 1)
        putInt16(h, 22, 1)
        putInt32(h, 24, 48000L)
        putInt32(h, 28, byteRate.toLong())
        putInt16(h, 32, blockAlign)
        putInt16(h, 34, 16)
        putAscii(h, 36, "data")
        putInt32(h, 40, 4294967295L)
        return h
    }

    private fun putAscii(h: ByteArray, offset: Int, s: String) {
        for (i in s.indices) h[offset + i] = s[i].code.toByte()
    }

    private fun putInt32(h: ByteArray, offset: Int, v: Long) {
        h[offset] = (v and 255).toByte()
        h[offset + 1] = ((v shr 8) and 255).toByte()
        h[offset + 2] = ((v shr 16) and 255).toByte()
        h[offset + 3] = ((v shr 24) and 255).toByte()
    }

    private fun putInt16(h: ByteArray, offset: Int, v: Int) {
        h[offset] = (v and 255).toByte()
        h[offset + 1] = ((v shr 8) and 255).toByte()
    }

    private fun parseSizeFromPath(path: String): Pair<Int, Int>? {
        val match = SIZE_REGEX.find(path) ?: return null
        val w = match.groupValues[1].toIntOrNull() ?: return null
        val h = match.groupValues[2].toIntOrNull() ?: return null
        if (w !in 160..4096) return null
        if (h !in 120..4096) return null
        return w to h
    }

    // ---------------------------------------------------------------- controls

    private fun handleControl(r: Request, out: BufferedOutputStream): Boolean {
        val path = r.path
        val q = r.query
        if (path.startsWith("/v1/camera/active")) {
            val index = controlIntValue(path, q, r, "active", -1)
            if (index < 0) return false
            control.switchCamera(index)
            writeText(out, index.toString())
            return true
        }
        if (path.startsWith("/v1/camera/mic_toggle")) {
            control.apply { it.micEnabled = !it.micEnabled }
            writeText(out, if (control.settings().micEnabled) "1" else "0")
            return true
        }
        if (path.startsWith("/v1/camera/torch_toggle")) {
            control.apply { it.torch = !it.torch }
            writeText(out, if (control.settings().torch) "1" else "0")
            return true
        }
        if (path.startsWith("/v1/camera/wb_mode")) {
            val mode = controlIntValue(path, q, r, "wb_mode", -1)
            if (mode < 0) return false
            control.apply {
                it.wbMode = CameraSettings.WB_MODES[mode.coerceIn(0, CameraSettings.WB_MODES.lastIndex)]
            }
            writeText(out, mode.toString())
            return true
        }
        if (path.startsWith("/v2/camera/wb_level")) {
            val level = controlFloatValue(path, q, r, "wb_level", 0.5f)
            control.apply { it.wbLevel = level.coerceIn(0f, 1f) }
            writeText(out, level.toString())
            return true
        }
        if (path.startsWith("/v1/camera/wbl_toggle")) {
            control.apply { it.wbLock = !it.wbLock }
            writeText(out, if (control.settings().wbLock) "1" else "0")
            return true
        }
        if (path.startsWith("/v1/camera/ae_toggle") || path.startsWith("/v1/camera/el_toggle")) {
            control.apply { it.aeLock = !it.aeLock }
            writeText(out, if (control.settings().aeLock) "1" else "0")
            return true
        }
        if (path.startsWith("/v1/camera/autofocus_mode")) {
            val mode = controlIntValue(path, q, r, "autofocus_mode", -1)
            if (mode < 0) return false
            control.apply {
                it.afMode = CameraSettings.AF_MODES[mode.coerceIn(0, CameraSettings.AF_MODES.lastIndex)]
            }
            writeText(out, mode.toString())
            return true
        }
        if (path.startsWith("/v1/camera/autofocus")) {
            control.apply { }
            writeText(out, "OK")
            return true
        }
        if (path.startsWith("/v3/camera/zoom/")) {
            control.apply { it.zoom = controlFloatValue(path, q, r, "zoom", it.zoom).coerceIn(0f, 1f) }
            writeText(out, control.settings().zoom.toString())
            return true
        }
        if (path.startsWith("/v3/camera/ev/")) {
            control.apply { it.ev = controlFloatValue(path, q, r, "ev", it.ev).coerceIn(-4f, 4f) }
            writeText(out, control.settings().ev.toString())
            return true
        }
        if (path.startsWith("/v3/camera/ss/")) {
            val ms = controlFloatValue(path, q, r, "ss", 0f)
            control.apply { it.exposureNs = if (ms <= 0f) 0L else (1_000_000.0f * ms).toLong() }
            writeText(out, ms.toString())
            return true
        }
        if (path.startsWith("/v3/camera/iso/")) {
            control.apply { it.iso = controlIntValue(path, q, r, "iso", 0).coerceAtLeast(0) }
            writeText(out, control.settings().iso.toString())
            return true
        }
        if (path.startsWith("/v3/camera/mf/")) {
            control.apply { it.manualFocus = controlFloatValue(path, q, r, "mf", 0f).coerceIn(0f, 1f) }
            writeText(out, control.settings().manualFocus.toString())
            return true
        }
        if (path.startsWith("/v1/tally")) {
            // The stock client announces itself with /v1/tally/program/, the
            // OpenCam client posts the numeric state.
            val value = controlValue(path, q, r, "tally")
            val state = when {
                value == null -> 0
                value.equals("program", true) -> 1
                value.equals("preview", true) -> 2
                value.equals("none", true) || value.equals("off", true) -> 0
                value.toFloatOrNull() != null -> value.toFloat().toInt().coerceIn(0, 2)
                else -> value.toIntOrNull()?.coerceIn(0, 2) ?: 0
            }
            control.setTally(state)
            writeText(out, control.tallyState().toString())
            return true
        }
        if (path.startsWith("/v1/restart")) {
            control.restartStreaming()
            writeText(out, "OK")
            return true
        }
        if (path.startsWith("/v1/stop")) {
            writeText(out, "OK")
            control.stopStreaming()
            return true
        }
        return false
    }

    /** Value for a control named [name], from the path, the query or the body. */
    private fun controlValue(path: String, q: Map<String, String>, r: Request, name: String): String? {
        val marker = "/$name/"
        val idx = path.indexOf(marker)
        if (idx >= 0) {
            val rest = path.substring(idx + marker.length)
            val seg = rest.substringBefore('/')
            if (seg.length > 0) return seg
        }
        q[name]?.takeIf { it.isNotEmpty() }?.let { return it }
        val body = r.body
        if (body.isNotEmpty()) {
            for (pair in body.split('&', ';', '\n')) {
                val eq = pair.indexOf('=')
                if (eq > 0 && pair.substring(0, eq).trim() == name) {
                    return pair.substring(eq + 1).trim()
                }
            }
        }
        return null
    }

    private fun controlFloatValue(
        path: String,
        q: Map<String, String>,
        r: Request,
        name: String,
        fallback: Float
    ): Float = controlValue(path, q, r, name)?.toFloatOrNull() ?: fallback

    private fun controlIntValue(
        path: String,
        q: Map<String, String>,
        r: Request,
        name: String,
        fallback: Int
    ): Int {
        val v = controlValue(path, q, r, name) ?: return fallback
        v.toFloatOrNull()?.let { return it.toInt() }
        v.toIntOrNull()?.let { return it }
        return fallback
    }

    companion object {
        const val TAG = "OpenCam/Http"

        /** Boundary used by both multipart MJPEG and the reference client. */
        const val BOUNDARY = "--BoundaryString"

        private val CRLF = "\r\n".toByteArray(Charsets.UTF_8)

        /** Packet id marking a decoder config blob (all bits set). */
        private const val CONFIG_TAG = -1L

        private const val HEAD_WAIT_MS = 5000
        private const val HEAD_IDLE_MS = 300

        /** Samples per AAC-LC access unit, used to pace the audio timestamps. */
        private const val AAC_FRAME_SAMPLES = 1024L
        private const val AUDIO_RATE = 48000L

        private val SIZE_REGEX = Regex("""/(\d{3,4})x(\d{3,4})(/|$)""")

        private val VIDEO_ROOTS =
            listOf("/v1/video", "/v2/video", "/v3/video", "/v4/video", "/v5/video", "/v6/video", "/v7/video")
    }
}
