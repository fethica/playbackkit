package com.fethica.playbackkit.sample.fixtures

import java.security.MessageDigest

/**
 * The PCM format every fixture uses: the canonical 44-byte RIFF/WAVE header, PCM, mono,
 * 8000 Hz, 16-bit little-endian. 16,000 bytes are one second of audio.
 */
public object Wav {
    public const val HEADER_BYTES: Int = 44
    public const val SAMPLE_RATE: Int = 8_000
    public const val BITS_PER_SAMPLE: Int = 16
    public const val CHANNELS: Int = 1
    public const val BYTES_PER_SECOND: Int = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8

    /**
     * The largest data chunk a RIFF header can declare and still keep its own size field
     * (36 + data) inside 32 bits, rounded down to a whole sample: 4,294,967,258 bytes,
     * about 74.6 hours at [BYTES_PER_SECOND]. The live-like stream declares it.
     */
    public const val MAX_DATA_BYTES: Long = 0xFFFF_FFDAL

    /** The 44-byte header for a data chunk of [dataBytes]. */
    public fun header(dataBytes: Long): ByteArray {
        require(dataBytes in 0..MAX_DATA_BYTES && dataBytes % 2 == 0L) { "dataBytes out of range: $dataBytes" }
        val out = ByteArray(HEADER_BYTES)
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        ascii(out, 0, "RIFF")
        le32(out, 4, 36 + dataBytes)
        ascii(out, 8, "WAVE")
        ascii(out, 12, "fmt ")
        le32(out, 16, 16) // PCM fmt chunk size
        le16(out, 20, 1) // audio format: PCM
        le16(out, 22, CHANNELS)
        le32(out, 24, SAMPLE_RATE.toLong())
        le32(out, 28, BYTES_PER_SECOND.toLong())
        le16(out, 32, blockAlign)
        le16(out, 34, BITS_PER_SAMPLE)
        ascii(out, 36, "data")
        le32(out, 40, dataBytes)
        return out
    }

    private fun ascii(out: ByteArray, at: Int, text: String) {
        for (i in text.indices) out[at + i] = text[i].code.toByte()
    }

    private fun le16(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun le32(out: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) out[at + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }
}

/**
 * One second of audio that every fixture repeats: a sine beep of [toneMillis] at [toneHz],
 * with a 10 ms linear fade at each end so it does not click, then silence for the rest of
 * the second. Peak amplitude [amplitude] (3000 is about -21 dBFS).
 *
 * The bytes are a pure function of the arguments. The sine goes through [StrictMath],
 * whose results are specified bit for bit on every JVM and on Android, so the same
 * pattern is produced on a laptop and on a phone. An integer frequency at 8000 Hz makes
 * every second end on a whole cycle, so the loop is seamless.
 */
public class TonePattern(
    public val toneHz: Int,
    public val toneMillis: Int,
    public val amplitude: Int = 3_000,
) {
    init {
        require(toneHz in 1 until Wav.SAMPLE_RATE / 2) { "toneHz must be below Nyquist" }
        require(toneMillis in 0..1_000) { "toneMillis must fit in one second" }
        require(amplitude in 0..Short.MAX_VALUE)
    }

    /** The one-second loop, [Wav.BYTES_PER_SECOND] bytes of little-endian samples. */
    internal val second: ByteArray by lazy { generate() }

    private fun generate(): ByteArray {
        val out = ByteArray(Wav.BYTES_PER_SECOND)
        val toneSamples = Wav.SAMPLE_RATE * toneMillis / 1_000
        val fadeSamples = minOf(Wav.SAMPLE_RATE / 100, toneSamples / 2)
        for (n in 0 until toneSamples) {
            val fromEdge = minOf(n, toneSamples - 1 - n)
            val envelope = if (fadeSamples > 0 && fromEdge < fadeSamples) fromEdge.toDouble() / fadeSamples else 1.0
            val phase = 2.0 * StrictMath.PI * toneHz * n / Wav.SAMPLE_RATE
            val sample = StrictMath.round(StrictMath.sin(phase) * amplitude * envelope).toInt()
            out[n * 2] = (sample and 0xFF).toByte()
            out[n * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        // The rest of the second is already zero: silence.
        return out
    }

    /** Copies [length] bytes of the endless loop, starting [dataOffset] bytes into it. */
    internal fun copyLooped(dataOffset: Long, into: ByteArray, at: Int, length: Int) {
        var written = 0
        var position = (dataOffset % second.size).toInt()
        while (written < length) {
            val n = minOf(length - written, second.size - position)
            System.arraycopy(second, position, into, at + written, n)
            written += n
            position = 0
        }
    }
}

/** A complete finite WAV file: [seconds] of [pattern] behind a header that declares them. */
public class FiniteTone(
    public val name: String,
    public val pattern: TonePattern,
    public val seconds: Int,
) {
    init {
        require(name.matches(Regex("[a-z0-9-]+"))) { "name must be URL-safe: $name" }
        require(seconds > 0)
    }

    public val path: String get() = "/finite/$name.wav"

    public val sizeBytes: Int get() = Wav.HEADER_BYTES + seconds * Wav.BYTES_PER_SECOND

    public val durationMs: Long get() = seconds * 1_000L

    /** The whole file. Callers get their own copy. */
    public fun bytes(): ByteArray = cached.copyOf()

    internal val cached: ByteArray by lazy {
        val out = ByteArray(sizeBytes)
        Wav.header((sizeBytes - Wav.HEADER_BYTES).toLong()).copyInto(out)
        pattern.copyLooped(0, out, Wav.HEADER_BYTES, sizeBytes - Wav.HEADER_BYTES)
        out
    }

    /** Lower-case hex SHA-256 of the file. */
    public val sha256: String by lazy { sha256Hex(cached) }
}

/**
 * A live-like stream: a header that declares [Wav.MAX_DATA_BYTES] of data, then [pattern]
 * looped for as long as anyone reads. The byte at any offset is the same on every
 * connection, so a load resumed at an offset continues the same audio.
 */
public class LiveTone(
    public val name: String,
    public val pattern: TonePattern,
) {
    init {
        require(name.matches(Regex("[a-z0-9-]+"))) { "name must be URL-safe: $name" }
    }

    public val path: String get() = "/live/$name.wav"

    private val header: ByteArray = Wav.header(Wav.MAX_DATA_BYTES)

    /** Fills [length] bytes of the stream starting at absolute byte [offset]. */
    public fun read(offset: Long, into: ByteArray, at: Int = 0, length: Int = into.size - at) {
        require(offset >= 0 && offset + length <= TOTAL_BYTES)
        var written = 0
        var position = offset
        while (written < length && position < Wav.HEADER_BYTES) {
            into[at + written] = header[position.toInt()]
            written++
            position++
        }
        if (written < length) {
            pattern.copyLooped(position - Wav.HEADER_BYTES, into, at + written, length - written)
        }
    }

    public companion object {
        /** Header plus the declared data: the length a byte range can name. */
        public const val TOTAL_BYTES: Long = Wav.HEADER_BYTES + Wav.MAX_DATA_BYTES
    }
}

/** Lower-case hex SHA-256 of [bytes]. */
public fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}
