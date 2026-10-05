package com.fethica.playbackkit.sample.fixtures

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny HTTP/1.1 server on 127.0.0.1 that serves the sample's fixtures.
 *
 * - **Finite file** ([FiniteTone.path]): GET and HEAD, `Content-Length`,
 *   `Accept-Ranges: bytes` and a single `Range: bytes=` request (206 with
 *   `Content-Range`, 416 when it cannot be satisfied; anything else is answered with the
 *   whole file, as RFC 9110 allows). Written as fast as the client reads.
 * - **Live-like streams** ([LiveTone.path] and its mirror): a WAV header declaring
 *   [Wav.MAX_DATA_BYTES], then the tone looped forever, with no `Content-Length`, so the
 *   body ends only when the connection closes. The server keeps [LIVE_LEAD_MS] of audio
 *   ahead of real time and then writes at real-time pace, like a live source that sends what
 *   it has. `Range: bytes=N-` is answered with 206 from offset N (the bytes at an offset
 *   never change), which is what lets a reconnecting client resume where it was.
 *
 * Every response closes the connection. A [Scenario] per path can be changed at any time
 * from any thread and applies to the next request. It binds the loopback address only.
 *
 * @param clock time for pacing, delays and stalls; tests pass a fake one.
 */
public class LoopbackServer(
    private val finite: FiniteTone = FixtureCatalog.finite,
    live: List<LiveTone> = FixtureCatalog.live,
    private val clock: ServerClock = ServerClock.SYSTEM,
) : Closeable {

    private val liveByPath: Map<String, LiveTone> = buildMap {
        for (tone in live) {
            put(tone.path, tone)
            put(FixtureCatalog.mirrorPath(tone), tone)
        }
    }

    /** Every path this server answers. */
    public val paths: List<String> = listOf(finite.path) + liveByPath.keys

    private val scenarios = ConcurrentHashMap<String, Scenario>()
    private val requestCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val openClients: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())
    private val connectionIds = AtomicInteger()

    @Volatile
    private var socket: ServerSocket? = null

    /** Bound port, or -1 before [start] and after [close]. */
    public val port: Int get() = socket?.takeUnless { it.isClosed }?.localPort ?: -1

    public val isRunning: Boolean get() = port != -1

    /** `http://127.0.0.1:<port>`. Throws before [start]. */
    public val baseUrl: String
        get() {
            check(isRunning) { "the loopback server is not running" }
            return "http://$HOST:$port"
        }

    /** The URL of [path] on this server. */
    public fun urlFor(path: String): String = baseUrl + path

    /**
     * Binds 127.0.0.1 and starts accepting.
     *
     * @param preferredPort a port to try first (the sample reuses its last one so item URLs
     *   stay the same across launches). 0 takes an ephemeral port; a busy preferred port
     *   falls back to an ephemeral one.
     * @return the bound port.
     */
    @Synchronized
    public fun start(preferredPort: Int = 0): Int {
        socket?.let { if (!it.isClosed) return it.localPort }
        val server = bind(preferredPort) ?: bind(0) ?: throw IOException("could not bind $HOST")
        socket = server
        Thread({ acceptLoop(server) }, "loopback-server-accept").apply {
            isDaemon = true
            start()
        }
        return server.localPort
    }

    private fun bind(port: Int): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(HOST), port), BACKLOG)
        }
    } catch (_: IOException) {
        null
    }

    /** Stops accepting and closes every open connection, live streams included. */
    @Synchronized
    override fun close() {
        runCatching { socket?.close() }
        socket = null
        // Iterated in place: a concurrent set's iterator tolerates connections ending meanwhile.
        for (client in openClients) runCatching { client.close() }
    }

    // ---------------------------------------------------------------- scenarios

    public fun scenario(path: String): Scenario = scenarios[path] ?: Scenario.NORMAL

    public fun setScenario(path: String, scenario: Scenario) {
        require(path in paths) { "unknown path: $path" }
        if (scenario == Scenario.NORMAL) scenarios.remove(path) else scenarios[path] = scenario
    }

    /** Back to normal on [path]. */
    public fun reset(path: String) {
        scenarios.remove(path)
    }

    /** Back to normal everywhere, and request counters to zero. */
    public fun resetAll() {
        scenarios.clear()
        requestCounts.clear()
    }

    /** Requests received on [path] since start or the last [resetAll]. */
    public fun requestCount(path: String): Int = requestCounts[path]?.get() ?: 0

    /** The scenario for this response; a one-shot scenario is consumed by taking it. */
    private fun takeScenario(path: String): Scenario {
        val current = scenarios[path] ?: return Scenario.NORMAL
        if (current.once) scenarios.remove(path, current)
        return current
    }

    // ---------------------------------------------------------------- serving

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                break // closed
            } catch (_: Exception) {
                continue
            }
            Thread({ serve(client) }, "loopback-server-${connectionIds.incrementAndGet()}").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(client: Socket) {
        openClients += client
        try {
            client.use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                socket.tcpNoDelay = true
                val request = readRequest(BufferedInputStream(socket.getInputStream())) ?: return
                respond(request, socket)
            }
        } catch (_: Exception) {
            // The client went away, a scenario cut the response, or the server closed.
            // Never rethrown: inside an app an uncaught exception on this thread would take
            // the whole process down.
        } finally {
            openClients -= client
        }
    }

    private fun respond(request: Request, socket: Socket) {
        val out = socket.getOutputStream()
        if (request.method != "GET" && request.method != "HEAD") {
            writeHead(out, 405, listOf("Allow" to "GET, HEAD", "Content-Length" to "0"))
            return
        }
        val path = request.path.substringBefore('?')
        val tone = liveByPath[path]
        if (tone == null && path != finite.path) {
            writeHead(out, 404, listOf("Content-Length" to "0"))
            return
        }
        requestCounts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
        val scenario = takeScenario(path)
        clock.sleep(scenario.delayFirstByteMs)
        scenario.failWithStatus?.let { status ->
            writeHead(out, status, listOf("Content-Length" to "0"))
            return
        }
        if (tone != null) respondLive(request, socket, tone, scenario) else respondFinite(request, socket, scenario)
    }

    private fun respondFinite(request: Request, socket: Socket, scenario: Scenario) {
        val out = socket.getOutputStream()
        val body = finite.cached
        val size = body.size.toLong()
        val common = listOf("Content-Type" to CONTENT_TYPE, "Accept-Ranges" to "bytes")
        // Range applies to GET only; HEAD describes the whole representation (RFC 9110,
        // section 14.2), so it answers as a full GET would.
        val range = if (request.method == "GET") request.headers["range"]?.let { parseRange(it, size) } else null
        val (start, endInclusive) = when (range) {
            null, RangeResult.Ignore -> {
                writeHead(out, 200, common + ("Content-Length" to size.toString()))
                0L to size - 1
            }
            RangeResult.Unsatisfiable -> {
                writeHead(out, 416, common + listOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
                return
            }
            is RangeResult.Slice -> {
                writeHead(
                    out,
                    206,
                    common + listOf(
                        "Content-Range" to "bytes ${range.start}-${range.endInclusive}/$size",
                        "Content-Length" to (range.endInclusive - range.start + 1).toString(),
                    ),
                )
                range.start to range.endInclusive
            }
        }
        if (request.method == "HEAD") return
        val total = endInclusive - start + 1
        var written = 0L
        var stalled = false
        while (written < total) {
            val audioMs = written * 1_000 / Wav.BYTES_PER_SECOND
            if (!stalled && scenario.stallAfterMs != null && audioMs >= scenario.stallAfterMs) {
                stalled = true
                clock.sleep(scenario.stallForMs)
            }
            if (scenario.disconnectAfterMs != null && audioMs >= scenario.disconnectAfterMs) {
                abort(socket)
                return
            }
            val n = minOf(CHUNK_BYTES.toLong(), total - written).toInt()
            out.write(body, (start + written).toInt(), n)
            out.flush()
            written += n
        }
    }

    private fun respondLive(request: Request, socket: Socket, tone: LiveTone, scenario: Scenario) {
        val out = socket.getOutputStream()
        val common = listOf("Content-Type" to CONTENT_TYPE, "Accept-Ranges" to "bytes", "Cache-Control" to "no-store")
        val total = LiveTone.TOTAL_BYTES
        val from = if (request.method == "GET") request.headers["range"]?.let { parseOpenRange(it) } else null
        val offset = when {
            from == null || from == 0L -> {
                writeHead(out, 200, common)
                0L
            }
            from >= total -> {
                writeHead(out, 416, common + listOf("Content-Range" to "bytes */$total", "Content-Length" to "0"))
                return
            }
            else -> {
                // No Content-Length on purpose: the body still ends only when the
                // connection closes, exactly as on the first request.
                writeHead(out, 206, common + ("Content-Range" to "bytes $from-${total - 1}/$total"))
                from
            }
        }
        if (request.method == "HEAD") return

        val chunk = ByteArray(CHUNK_BYTES)
        var written = 0L
        var paceStart = clock.nowMs()
        var stalled = false
        while (offset + written < total) {
            val audioMs = written * 1_000 / Wav.BYTES_PER_SECOND
            if (!stalled && scenario.stallAfterMs != null && audioMs >= scenario.stallAfterMs) {
                stalled = true
                clock.sleep(scenario.stallForMs)
                // Resume at real-time pace rather than bursting the stalled seconds.
                paceStart += scenario.stallForMs
            }
            if (scenario.disconnectAfterMs != null && audioMs >= scenario.disconnectAfterMs) {
                abort(socket)
                return
            }
            if (scenario.endAfterMs != null && audioMs >= scenario.endAfterMs) return
            val due = paceStart + audioMs - LIVE_LEAD_MS
            val now = clock.nowMs()
            if (due > now) clock.sleep(due - now)
            val n = minOf(CHUNK_BYTES.toLong(), total - offset - written).toInt()
            tone.read(offset + written, chunk, 0, n)
            out.write(chunk, 0, n)
            out.flush()
            written += n
        }
    }

    /** Resets the connection: SO_LINGER 0 makes close() send RST instead of FIN. */
    private fun abort(socket: Socket) {
        runCatching { socket.setSoLinger(true, 0) }
        socket.close()
    }

    private fun writeHead(out: OutputStream, status: Int, headers: List<Pair<String, String>>) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>)

    /** Reads the request line and headers; the server never needs a request body. */
    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            if (headers.size > MAX_HEADERS) return null
        }
        return Request(parts[0].uppercase(), parts[1], headers)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) return null
        }
    }

    internal sealed interface RangeResult {
        data object Ignore : RangeResult
        data object Unsatisfiable : RangeResult
        data class Slice(val start: Long, val endInclusive: Long) : RangeResult
    }

    public companion object {
        public const val HOST: String = "127.0.0.1"
        public const val CONTENT_TYPE: String = "audio/wav"

        /** How far ahead of real time a live response runs: what a client can buffer. */
        public const val LIVE_LEAD_MS: Long = 2_000L

        /** 100 ms of audio per write. */
        public const val CHUNK_BYTES: Int = Wav.BYTES_PER_SECOND / 10

        private const val BACKLOG = 16
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_LINE = 8_192
        private const val MAX_HEADERS = 100

        /**
         * One `bytes=` range against a body of [size] bytes. Anything malformed, any other
         * unit and any multi-range request is ignored (the whole body is served).
         */
        internal fun parseRange(header: String, size: Long): RangeResult {
            val value = header.trim()
            if (!value.startsWith("bytes=", ignoreCase = true)) return RangeResult.Ignore
            val spec = value.substring(6).trim()
            if (spec.contains(',')) return RangeResult.Ignore
            val dash = spec.indexOf('-')
            if (dash < 0) return RangeResult.Ignore
            val first = spec.substring(0, dash).trim()
            val last = spec.substring(dash + 1).trim()
            if (first.isEmpty()) {
                // Suffix range: the last N bytes.
                val n = last.toLongOrNull() ?: return RangeResult.Ignore
                if (n <= 0 || size == 0L) return RangeResult.Unsatisfiable
                return RangeResult.Slice(maxOf(0, size - n), size - 1)
            }
            val start = first.toLongOrNull() ?: return RangeResult.Ignore
            val end = if (last.isEmpty()) null else last.toLongOrNull() ?: return RangeResult.Ignore
            if (start < 0 || (end != null && end < start)) return RangeResult.Ignore
            // Checked before clamping the end: "bytes=<size>-" is unsatisfiable, not invalid.
            if (start >= size) return RangeResult.Unsatisfiable
            return RangeResult.Slice(start, minOf(end ?: (size - 1), size - 1))
        }

        /**
         * The start of a `bytes=N-` or `bytes=N-M` range on a live stream, or null to serve
         * from the start. The end is ignored: a live body runs until the connection closes.
         */
        internal fun parseOpenRange(header: String): Long? {
            val value = header.trim()
            if (!value.startsWith("bytes=", ignoreCase = true)) return null
            val spec = value.substring(6).trim()
            if (spec.contains(',')) return null
            val dash = spec.indexOf('-')
            if (dash <= 0) return null
            return spec.substring(0, dash).trim().toLongOrNull()?.takeIf { it >= 0 }
        }

        internal fun reasonPhrase(status: Int): String = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            410 -> "Gone"
            416 -> "Range Not Satisfiable"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Error"
        }
    }
}
