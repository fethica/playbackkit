package com.fethica.playbackkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transport rules: an ended item is not playing although media3 keeps playWhenReady
 * true, and one tap on play/pause always does the useful thing.
 */
class TransportTest {

    private val idle = PLAYBACK_STATE_IDLE
    private val buffering = 2
    private val ready = 3

    @Test
    fun `a ready item with playWhenReady is playing`() {
        assertTrue(
            isEffectivelyPlaying(
                playWhenReady = true,
                isActuallyPlaying = true,
                playbackState = ready
            )
        )
        assertTrue(
            isEffectivelyPlaying(
                playWhenReady = true,
                isActuallyPlaying = false,
                playbackState = buffering
            )
        )
    }

    @Test
    fun `an ended item is not playing even though playWhenReady stays true`() {
        assertFalse(
            isEffectivelyPlaying(
                playWhenReady = true,
                isActuallyPlaying = false,
                playbackState = PLAYBACK_STATE_ENDED
            )
        )
    }

    @Test
    fun `a paused item is not playing`() {
        assertFalse(
            isEffectivelyPlaying(
                playWhenReady = false,
                isActuallyPlaying = false,
                playbackState = ready
            )
        )
        assertFalse(
            isEffectivelyPlaying(
                playWhenReady = false,
                isActuallyPlaying = false,
                playbackState = idle
            )
        )
    }

    @Test
    fun `a connected snapshot trusts actual playback while intent catches up`() {
        assertTrue(
            isEffectivelyPlaying(
                playWhenReady = false,
                isActuallyPlaying = true,
                playbackState = ready
            )
        )
    }

    @Test
    fun `finite duration falls back to a known duration on reconnect`() {
        assertEquals(1_325_208L, effectivePlaybackDurationMs(1_325_208L, 1_325))
        assertEquals(1_325_000L, effectivePlaybackDurationMs(0L, 1_325))
        assertEquals(1_325_000L, effectivePlaybackDurationMs(Long.MIN_VALUE, 1_325))
        assertEquals(0L, effectivePlaybackDurationMs(0L, null))
    }

    @Test
    fun `an ended item restarts from the beginning in one tap`() {
        assertEquals(
            PlayPauseAction.RESTART_FROM_END,
            playPauseActionFor(playWhenReady = true, playbackState = PLAYBACK_STATE_ENDED)
        )
        assertEquals(
            PlayPauseAction.RESTART_FROM_END,
            playPauseActionFor(playWhenReady = false, playbackState = PLAYBACK_STATE_ENDED)
        )
    }

    @Test
    fun `a playing item pauses`() {
        assertEquals(
            PlayPauseAction.PAUSE,
            playPauseActionFor(playWhenReady = true, playbackState = ready)
        )
        assertEquals(
            PlayPauseAction.PAUSE,
            playPauseActionFor(playWhenReady = true, playbackState = buffering)
        )
    }

    @Test
    fun `an idle item is prepared before it plays`() =
        assertEquals(
            PlayPauseAction.PREPARE_AND_PLAY,
            playPauseActionFor(playWhenReady = false, playbackState = PLAYBACK_STATE_IDLE)
        )

    @Test
    fun `a paused item just plays`() =
        assertEquals(
            PlayPauseAction.PLAY,
            playPauseActionFor(playWhenReady = false, playbackState = ready)
        )
}
