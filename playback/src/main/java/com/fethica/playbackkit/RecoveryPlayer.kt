package com.fethica.playbackkit

/**
 * The narrow view of a player that recovery reads and drives.
 *
 * Kept to plain types so the recovery logic runs on the JVM against a fake, and so the
 * Media3 adapter is the only place that touches `Player`. Every member is called on the
 * player's application looper.
 */
internal interface RecoveryPlayer {
    val playbackState: Int
    val playWhenReady: Boolean
    val isPlaying: Boolean
    val playbackSuppressionReason: Int
    val hasPlayerError: Boolean
    val mediaItemCount: Int
    val currentMediaItemIndex: Int
    val currentMediaId: String?
    val currentUri: String?
    val currentPosition: Long

    /**
     * An identity for the current item's place in the playlist, or null when empty. A new
     * one appears the moment an item is set, even with the same media id, before any
     * callback reports it; an in-place URI swap of the same entry may keep it.
     */
    val currentItemToken: Any?

    fun mediaIdAt(index: Int): String?
    fun uriAt(index: Int): String?

    fun stop()
    fun seekTo(index: Int, positionMs: Long)
    fun prepare()
    fun play()
    fun pause()

    /** Replaces the URI of the item at [index] in place, keeping its media id. */
    fun replaceUri(index: Int, uri: String)
}

/** The player callbacks recovery answers to, in plain types. */
internal interface RecoveryCallbacks {
    fun onMediaItemTransition(mediaId: String?, reason: Int)
    fun onPlayerError(errorCode: Int, errorCodeName: String, httpStatus: Int?)
    fun onPlaybackStateChanged(playbackState: Int)
    fun onIsPlayingChanged(isPlaying: Boolean)
    fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int)
    fun onPositionDiscontinuity(reason: Int)
}

/** `Player.STATE_BUFFERING`. */
internal const val STATE_BUFFERING: Int = 2

/** `Player.STATE_READY`. */
internal const val STATE_READY: Int = 3

/** `Player.DISCONTINUITY_REASON_SEEK`. */
internal const val DISCONTINUITY_REASON_SEEK: Int = 1
