package com.fethica.playbackkit.sample.fixtures

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Talks raw HTTP over a socket rather than through HttpURLConnection, so what is checked
 * is exactly what the server put on the wire: status, headers, body bytes, a clean end
 * or a reset. The server runs on a [FakeClock], so pacing, delays and stalls are checked
 * by the sleeps they asked for instead of by waiting.
 */
class LoopbackServerTest {

    private lateinit var clock: FakeClock
    private lateinit var server: LoopbackServer
    private val finite = FixtureCatalog.finite
    private val live = FixtureCatalog.liveA

    @Before
    fun setUp() {
        clock = FakeClock()
        server = LoopbackServer(clock = clock)
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ---------------------------------------------------------------- basics

    @Test
    fun bindsLoopbackOnAnEphemeralPort() {
        assertTrue(server.port in 1..65_535)
        assertTrue(server.baseUrl.startsWith("http://127.0.0.1:"))
    }

    @Test
    fun startReusesAFreePreferredPort() {
        val port = server.port
        server.close()
        val again = LoopbackServer(clock = clock)
        try {
            assertEquals(port, again.start(preferredPort = port))
        } finally {
            again.close()
        }
    }

    @Test
    fun unknownPathIs404AndOtherMethodsAre405() {
        assertEquals(404, request("GET", "/nothing.wav").status)
        val post = request("POST", finite.path)
        assertEquals(405, post.status)
        assertEquals("GET, HEAD", post.headers["allow"])
    }

    // ---------------------------------------------------------------- finite file

    @Test
    fun finiteGetReturnsTheWholeFile() {
        val response = request("GET", finite.path)
        assertEquals(200, response.status)
        assertEquals(finite.sizeBytes.toString(), response.headers["content-length"])
        assertEquals("bytes", response.headers["accept-ranges"])
        assertEquals("audio/wav", response.headers["content-type"])
        assertEquals(FixtureCatalog.FINITE_SHA256, sha256Hex(response.body))
        assertTrue(response.cleanEnd)
    }

    @Test
    fun finiteRangeReturnsTheSlice() {
        val response = request("GET", finite.path, "Range" to "bytes=1000-1999")
        assertEquals(206, response.status)
        assertEquals("bytes 1000-1999/${finite.sizeBytes}", response.headers["content-range"])
        assertEquals("1000", response.headers["content-length"])
        assertArrayEquals(finite.bytes().copyOfRange(1000, 2000), response.body)
    }

    @Test
    fun finiteOpenEndedAndSuffixRanges() {
        val size = finite.sizeBytes
        val open = request("GET", finite.path, "Range" to "bytes=${size - 10}-")
        assertEquals(206, open.status)
        assertEquals("bytes ${size - 10}-${size - 1}/$size", open.headers["content-range"])
        assertArrayEquals(finite.bytes().copyOfRange(size - 10, size), open.body)

        val suffix = request("GET", finite.path, "Range" to "bytes=-4")
        assertEquals(206, suffix.status)
        assertArrayEquals(finite.bytes().copyOfRange(size - 4, size), suffix.body)
    }

    @Test
    fun finiteUnsatisfiableRangeIs416() {
        val response = request("GET", finite.path, "Range" to "bytes=${finite.sizeBytes}-")
        assertEquals(416, response.status)
        assertEquals("bytes */${finite.sizeBytes}", response.headers["content-range"])
        assertEquals(0, response.body.size)
    }

    @Test
    fun finiteHeadReturnsTheLengthWithoutABodyAndIgnoresRange() {
        val response = request("HEAD", finite.path, "Range" to "bytes=0-9")
        assertEquals(200, response.status)
        assertEquals(finite.sizeBytes.toString(), response.headers["content-length"])
        assertNull(response.headers["content-range"])
        assertEquals(0, response.body.size)
    }

    // The macOS loopback occasionally drops the RST of an abortive close, and the client
    // then sees its read timeout instead of the reset. Either way no byte past the cut
    // arrives, which is what these two tests check; the short timeout keeps them quick.
    @Test
    fun finiteDisconnectResetsTheConnectionEarly() {
        server.setScenario(finite.path, Scenario(disconnectAfterMs = 3_000))
        val response = request("GET", finite.path, timeoutMs = 1_000)
        assertEquals(200, response.status)
        assertTrue("got ${response.body.size}", response.body.size <= 3 * Wav.BYTES_PER_SECOND)
        assertTrue(response.body.size < finite.sizeBytes)
    }

    @Test
    fun finiteStallSleepsOnceAndThenFinishes() {
        server.setScenario(finite.path, Scenario(stallAfterMs = 3_000, stallForMs = 9_000))
        val response = request("GET", finite.path)
        assertEquals(FixtureCatalog.FINITE_SHA256, sha256Hex(response.body))
        assertEquals(listOf(9_000L), clock.sleepsSnapshot())
    }

    // ---------------------------------------------------------------- live-like stream

    @Test
    fun liveGetHasNoLengthAndStreamsTheDeterministicBytes() {
        val response = request("GET", live.path, readLimit = 5 * Wav.BYTES_PER_SECOND)
        assertEquals(200, response.status)
        assertNull(response.headers["content-length"])
        assertEquals("no-store", response.headers["cache-control"])
        val expected = ByteArray(response.body.size)
        live.read(0, expected)
        assertArrayEquals(expected, response.body)
    }

    @Test
    fun liveIsPacedAtRealTimeAfterTheLead() {
        val start = clock.nowMs()
        val seconds = 6
        request("GET", live.path, readLimit = seconds * Wav.BYTES_PER_SECOND)
        // Writing the 6th second needs at least 6 s - lead of clock time; each wait is a
        // fraction of one chunk, never a burst.
        assertTrue(clock.elapsedSince(start) >= seconds * 1_000L - LoopbackServer.LIVE_LEAD_MS - 100)
        assertTrue(clock.sleepsSnapshot().all { it <= 100 })
    }

    @Test
    fun liveRangeResumesAtTheOffsetWith206() {
        val from = 16_044L
        val response = request("GET", live.path, "Range" to "bytes=$from-", readLimit = 8_000)
        assertEquals(206, response.status)
        assertEquals("bytes $from-${LiveTone.TOTAL_BYTES - 1}/${LiveTone.TOTAL_BYTES}", response.headers["content-range"])
        assertNull(response.headers["content-length"])
        val expected = ByteArray(response.body.size)
        live.read(from, expected)
        assertArrayEquals(expected, response.body)
    }

    @Test
    fun liveHeadHasHeadersOnly() {
        val response = request("HEAD", live.path)
        assertEquals(200, response.status)
        assertNull(response.headers["content-length"])
        assertEquals(0, response.body.size)
        assertTrue(response.cleanEnd)
    }

    @Test
    fun liveMirrorServesTheSameBytesWithItsOwnScenario() {
        val mirror = FixtureCatalog.mirrorPath(live)
        server.setScenario(live.path, Scenario(failWithStatus = 404))
        assertEquals(404, request("GET", live.path).status)
        val response = request("GET", mirror, readLimit = 4_000)
        assertEquals(200, response.status)
        val expected = ByteArray(response.body.size)
        live.read(0, expected)
        assertArrayEquals(expected, response.body)
    }

    @Test
    fun liveEndClosesCleanlyAfterExactlyThatMuchAudio() {
        server.setScenario(live.path, Scenario(endAfterMs = 1_000))
        val response = request("GET", live.path)
        assertEquals(200, response.status)
        assertTrue(response.cleanEnd)
        assertEquals(Wav.BYTES_PER_SECOND, response.body.size)
    }

    @Test
    fun liveDisconnectStopsTheStreamEarly() {
        server.setScenario(live.path, Scenario(disconnectAfterMs = 1_000))
        val response = request("GET", live.path, readLimit = 10 * Wav.BYTES_PER_SECOND, timeoutMs = 1_000)
        assertEquals(200, response.status)
        assertTrue("got ${response.body.size}", response.body.size <= Wav.BYTES_PER_SECOND)
    }

    @Test
    fun liveStallSleepsForItsDurationThenResumesAtPace() {
        server.setScenario(live.path, Scenario(stallAfterMs = 1_000, stallForMs = 7_000))
        val start = clock.nowMs()
        val response = request("GET", live.path, readLimit = 4 * Wav.BYTES_PER_SECOND)
        assertEquals(4 * Wav.BYTES_PER_SECOND, response.body.size)
        val sleeps = clock.sleepsSnapshot()
        assertEquals(1, sleeps.count { it == 7_000L })
        // The stall is not made up with a burst: pacing restarts from the stall's end.
        assertTrue(clock.elapsedSince(start) >= 7_000L + 4_000L - LoopbackServer.LIVE_LEAD_MS - 100)
    }

    // ---------------------------------------------------------------- switches

    @Test
    fun delayedFirstByteSleepsBeforeTheStatusLine() {
        server.setScenario(finite.path, Scenario(delayFirstByteMs = 20_000))
        val response = request("HEAD", finite.path)
        assertEquals(200, response.status)
        assertEquals(listOf(20_000L), clock.sleepsSnapshot())
    }

    @Test
    fun statusSwitchesApplyToEveryRequestUntilReset() {
        server.setScenario(live.path, Scenario(failWithStatus = 500))
        assertEquals(500, request("GET", live.path).status)
        assertEquals(500, request("GET", live.path).status)
        server.setScenario(finite.path, Scenario(failWithStatus = 404))
        assertEquals(404, request("GET", finite.path).status)
        server.reset(live.path)
        assertEquals(200, request("HEAD", live.path).status)
        assertEquals(404, request("GET", finite.path).status)
        server.resetAll()
        assertEquals(200, request("GET", finite.path).status)
    }

    @Test
    fun onceFailsTheNextRequestAndThenServesAgain() {
        server.setScenario(live.path, Scenario(failWithStatus = 500, once = true))
        assertEquals(500, request("GET", live.path).status)
        assertEquals(200, request("HEAD", live.path).status)
        assertEquals(Scenario.NORMAL, server.scenario(live.path))
    }

    @Test
    fun requestCountsAreKeptPerPathUntilResetAll() {
        request("HEAD", finite.path)
        request("HEAD", finite.path)
        request("HEAD", live.path)
        assertEquals(2, server.requestCount(finite.path))
        assertEquals(1, server.requestCount(live.path))
        server.resetAll()
        assertEquals(0, server.requestCount(finite.path))
    }

    @Test
    fun closeEndsAnOpenLiveStream() {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LoopbackServer.HOST, server.port), 5_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET ${live.path} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val input = socket.getInputStream()
            input.read(ByteArray(1_000))
            server.close()
            // Without close the stream would run forever and this read would time out.
            val ended = try {
                while (input.read(ByteArray(8_192)) != -1) Unit
                true
            } catch (_: SocketTimeoutException) {
                false
            } catch (_: IOException) {
                true // reset
            }
            assertTrue(ended)
        }
    }

    @Test
    fun parseOpenRange() {
        assertEquals(10L, LoopbackServer.parseOpenRange("bytes=10-"))
        assertEquals(10L, LoopbackServer.parseOpenRange("bytes=10-20"))
        assertNull(LoopbackServer.parseOpenRange("bytes=-10"))
        assertNull(LoopbackServer.parseOpenRange("bytes=1-2,4-5"))
        assertNull(LoopbackServer.parseOpenRange("items=1-"))
    }

    // ---------------------------------------------------------------- client

    private class Response(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
        /** The server closed with FIN (not a reset, not cut short by readLimit). */
        val cleanEnd: Boolean,
    )

    private fun request(
        method: String,
        path: String,
        vararg headers: Pair<String, String>,
        readLimit: Int = Int.MAX_VALUE,
        timeoutMs: Int = 5_000,
    ): Response {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LoopbackServer.HOST, server.port), 5_000)
            socket.soTimeout = timeoutMs
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n")
                for ((name, value) in headers) append("$name: $value\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(head.toByteArray(Charsets.US_ASCII))
            val input = socket.getInputStream()
            val statusLine = readLine(input)
            val status = statusLine.split(' ')[1].toInt()
            val responseHeaders = HashMap<String, String>()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                responseHeaders[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val body = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var clean = false
            try {
                while (body.size() < readLimit) {
                    val n = input.read(buffer, 0, minOf(buffer.size, readLimit - body.size()))
                    if (n == -1) {
                        clean = true
                        break
                    }
                    body.write(buffer, 0, n)
                }
            } catch (_: IOException) {
                // A reset (or, for a lost reset, the read timeout): what arrived is the body.
            }
            return Response(status, responseHeaders, body.toByteArray(), clean)
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }
}
