package com.fethica.playbackkit.sample.fixtures

/**
 * How the server misbehaves on one path. The default is a well-behaved server. Audio
 * times count from the first body byte of each response, at [Wav.BYTES_PER_SECOND].
 *
 * @param delayFirstByteMs sleep this long after reading the request, before the status line.
 * @param failWithStatus answer with this status (404, 500, ...) and an empty body.
 * @param stallAfterMs after this much audio, stop writing for [stallForMs] and then carry
 *   on; the connection stays open.
 * @param stallForMs how long a stall lasts.
 * @param disconnectAfterMs after this much audio, reset the connection (TCP RST): the
 *   client sees a read error, not an end of stream.
 * @param endAfterMs live paths only: after this much audio, close the connection cleanly.
 *   The live response has no Content-Length, so the client sees a normal end of stream.
 * @param once apply this scenario to the next response only, then serve normally again:
 *   a server that fails and then reconnects.
 */
public data class Scenario(
    val delayFirstByteMs: Long = 0,
    val failWithStatus: Int? = null,
    val stallAfterMs: Long? = null,
    val stallForMs: Long = 0,
    val disconnectAfterMs: Long? = null,
    val endAfterMs: Long? = null,
    val once: Boolean = false,
) {
    init {
        require(delayFirstByteMs >= 0 && stallForMs >= 0)
        require(failWithStatus == null || failWithStatus in 400..599) { "failWithStatus must be 4xx or 5xx" }
        require(stallAfterMs == null || stallAfterMs >= 0)
        require(disconnectAfterMs == null || disconnectAfterMs >= 0)
        require(endAfterMs == null || endAfterMs >= 0)
    }

    public val isDefault: Boolean get() = copy(once = false) == NORMAL

    public companion object {
        public val NORMAL: Scenario = Scenario()
    }
}

/** Time for the server, injectable so tests can check pacing and stalls without waiting. */
public interface ServerClock {
    /** A monotonic time in milliseconds. */
    public fun nowMs(): Long

    /** Blocks for [ms] milliseconds. */
    public fun sleep(ms: Long)

    public companion object {
        public val SYSTEM: ServerClock = object : ServerClock {
            override fun nowMs(): Long = System.nanoTime() / 1_000_000

            override fun sleep(ms: Long) {
                if (ms > 0) Thread.sleep(ms)
            }
        }
    }
}
