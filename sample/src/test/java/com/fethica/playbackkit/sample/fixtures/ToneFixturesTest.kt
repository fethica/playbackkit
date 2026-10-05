package com.fethica.playbackkit.sample.fixtures

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ToneFixturesTest {

    @Test
    fun finiteFileMatchesItsPinnedHashAndSize() {
        val finite = FixtureCatalog.finite
        assertEquals(480_044, finite.sizeBytes)
        assertEquals(finite.sizeBytes, finite.bytes().size)
        assertEquals(FixtureCatalog.FINITE_SHA256, finite.sha256)
    }

    @Test
    fun liveLoopsMatchTheirPinnedHashes() {
        for (tone in FixtureCatalog.live) {
            assertEquals(tone.name, FixtureCatalog.LOOP_SHA256[tone.name], sha256Hex(tone.pattern.second))
        }
    }

    @Test
    fun generationIsDeterministic() {
        val again = FiniteTone("tone-30s", TonePattern(toneHz = 523, toneMillis = 250), seconds = 30)
        assertArrayEquals(FixtureCatalog.finite.bytes(), again.bytes())
    }

    @Test
    fun finiteHeaderDescribesThePcmData() {
        val header = ByteBuffer.wrap(FixtureCatalog.finite.bytes(), 0, Wav.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val bytes = FixtureCatalog.finite.bytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(36 + 480_000, header.getInt(4))
        assertEquals(1, header.getShort(20).toInt()) // PCM
        assertEquals(1, header.getShort(22).toInt()) // mono
        assertEquals(8_000, header.getInt(24))
        assertEquals(16_000, header.getInt(28))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(480_000, header.getInt(40))
    }

    @Test
    fun liveHeaderDeclaresTheMaximumDataSize() {
        val head = ByteArray(Wav.HEADER_BYTES)
        FixtureCatalog.liveA.read(0, head)
        val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xFFFF_FFFEL, buffer.getInt(4).toLong() and 0xFFFF_FFFFL)
        assertEquals(Wav.MAX_DATA_BYTES, buffer.getInt(40).toLong() and 0xFFFF_FFFFL)
        assertEquals(0L, Wav.MAX_DATA_BYTES % 2)
    }

    @Test
    fun liveBytesAreAFunctionOfTheOffset() {
        val tone = FixtureCatalog.liveA
        val whole = ByteArray(Wav.HEADER_BYTES + 3 * Wav.BYTES_PER_SECOND)
        tone.read(0, whole)
        // Any window, read on its own, equals the same window of a longer read.
        for (offset in listOf(1L, 43L, 44L, 45L, 16_043L, 16_044L, 31_999L)) {
            val window = ByteArray(5_000)
            tone.read(offset, window)
            assertArrayEquals("offset $offset", whole.copyOfRange(offset.toInt(), offset.toInt() + 5_000), window)
        }
        // The data loops every second.
        val first = whole.copyOfRange(Wav.HEADER_BYTES, Wav.HEADER_BYTES + Wav.BYTES_PER_SECOND)
        val second = whole.copyOfRange(Wav.HEADER_BYTES + Wav.BYTES_PER_SECOND, Wav.HEADER_BYTES + 2 * Wav.BYTES_PER_SECOND)
        assertArrayEquals(first, second)
    }

    @Test
    fun toneIsQuietFadedAndThenSilent() {
        val pattern = FixtureCatalog.finite.pattern
        val samples = ByteBuffer.wrap(pattern.second).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var peak = 0
        for (i in 0 until samples.limit()) peak = maxOf(peak, kotlin.math.abs(samples.get(i).toInt()))
        assertTrue("peak $peak", peak in 2_900..3_000)
        assertEquals(0, samples.get(0).toInt()) // fade in starts at zero
        val toneSamples = Wav.SAMPLE_RATE * pattern.toneMillis / 1_000
        for (i in toneSamples until samples.limit()) assertEquals(0, samples.get(i).toInt())
    }
}
