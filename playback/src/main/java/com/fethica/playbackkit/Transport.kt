package com.fethica.playbackkit

/** `Player.STATE_IDLE`, as a plain Int so the transport rules need no media3 type. */
public const val PLAYBACK_STATE_IDLE: Int = 1

/** `Player.STATE_ENDED`, as a plain Int so the transport rules need no media3 type. */
public const val PLAYBACK_STATE_ENDED: Int = 4

/**
 * Whether a UI should present the player as playing.
 *
 * Media3 leaves `playWhenReady` true when a finite item reaches its end, so taking the
 * flag at face value shows a pause control for audio that has already finished. An ended
 * item is not playing, whatever the flag says. Do not read `isPlaying == false` as a
 * pause either: a buffering item with play requested is still meant to be playing.
 */
public fun isEffectivelyPlaying(
    playWhenReady: Boolean,
    isActuallyPlaying: Boolean,
    playbackState: Int,
): Boolean = playbackState != PLAYBACK_STATE_ENDED && (isActuallyPlaying || playWhenReady)

/**
 * The duration a UI shows for a finite item while a controller is attaching.
 *
 * A newly connected controller can expose the media item before its timeline, so the
 * reported duration is still unset on the first snapshot. Falling back to a duration the
 * host already knows (for example from the item's extras) keeps progress UI available.
 */
public fun effectivePlaybackDurationMs(reportedDurationMs: Long, fallbackDurationSeconds: Int?): Long {
    if (reportedDurationMs > 0L) return reportedDurationMs
    return fallbackDurationSeconds?.coerceAtLeast(0)?.times(1_000L) ?: 0L
}

/** What one tap on play/pause should actually do. */
public enum class PlayPauseAction {
    PAUSE,

    /** A finite item that has already finished: seek to zero and play, in one tap. */
    RESTART_FROM_END,

    /** Stopped or never prepared, so `play()` alone would do nothing. */
    PREPARE_AND_PLAY,

    PLAY,
}

/**
 * The action for a play/pause tap, decided from the pair that actually describes the
 * player.
 *
 * STATE_ENDED is answered first because media3 leaves playWhenReady true once an item
 * finishes. A paused or still-buffering player is answered by playWhenReady, so buffering
 * can be paused, and an IDLE player (after a give-up, for example) is prepared and played
 * in one tap.
 */
public fun playPauseActionFor(playWhenReady: Boolean, playbackState: Int): PlayPauseAction = when {
    playbackState == PLAYBACK_STATE_ENDED -> PlayPauseAction.RESTART_FROM_END
    playWhenReady -> PlayPauseAction.PAUSE
    playbackState == PLAYBACK_STATE_IDLE -> PlayPauseAction.PREPARE_AND_PLAY
    else -> PlayPauseAction.PLAY
}
