package com.fethica.playbackkit

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher

/**
 * Playback recovery for one Media3 [Player]: a retry ladder for live items, a bounded
 * recovery at the retained position for finite items, buffering and stall watchdogs that
 * do not record failures while playback is only suppressed, a network-arrival handoff,
 * and a ladder that never resets on a READY blip: it resets when a wall-clock window
 * started at READY ends with the player playing (see [RecoveryConfig.stablePlaybackMs]).
 *
 * ## Lifecycle
 *
 * One owner. Create it with [attach] on the player's application looper, after the
 * player is built, and call [detach] before the player is released. The player may
 * already hold a prepared item: [attach] takes its current item as the baseline (not as a
 * new selection, so nothing is reset or reported) and arms the watchdog its current state
 * calls for, the stall check when READY with play requested and the buffering timeout
 * when BUFFERING. [detach] cancels
 * every rung, watchdog and pending network handoff and is idempotent; anything that
 * arrives afterwards is dropped. Attach to the player that actually plays (the one whose
 * errors and states recovery must see), not to a wrapper that hides them.
 *
 * ## Threading
 *
 * Every member except [onNetworkArrival] must be called on the player's application
 * looper, and every [RecoveryHost] method is called there. [onNetworkArrival] may be
 * called from any thread, for example straight from a `ConnectivityManager.NetworkCallback`:
 * it posts to the looper and never touches the player from the calling thread.
 *
 * ## Listener ordering
 *
 * By default [attach] registers [listener] on the player, so recovery hears callbacks in
 * registration order with the host's own listeners. When the order matters (a host that
 * must handle the end of an item, or start the next one, before recovery sees ENDED),
 * pass `registerListener = false` and call [listener]'s methods from the host's own
 * listener, in the order the host chooses.
 *
 * ## Intent generation
 *
 * [generation] increases on every new playback intent and tags every [RecoveryEvent]. It
 * is additive: delayed work is still validated when it runs by the same checks as always
 * (same media id, play still requested, the host's veto, suppression), not by the
 * generation alone.
 */
public class PlaybackRecovery private constructor(
    private val player: Player,
    private val engine: RecoveryEngine,
    private val registeredListener: Boolean,
) {
    /**
     * The listener recovery answers to. Registered on the player by [attach] unless
     * `registerListener` was false, in which case the host forwards its own callbacks.
     */
    public val listener: Player.Listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            engine.onMediaItemTransition(mediaItem?.mediaId, reason)
        }

        override fun onPlayerError(error: PlaybackException) {
            val httpStatus = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            engine.onPlayerError(error.errorCode, error.errorCodeName, httpStatus)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            engine.onPlaybackStateChanged(playbackState)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            engine.onIsPlayingChanged(isPlaying)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            engine.onPlayWhenReadyChanged(playWhenReady, reason)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            engine.onPositionDiscontinuity(reason)
        }
    }

    /** The current playback-intent generation. */
    public val generation: Long get() = engine.generation

    /** False once [detach] has run. */
    public val isAttached: Boolean get() = !engine.isDetached

    /**
     * A network became available: any `onAvailable`, including a handover from one network
     * to another that never passed through "offline". Resumes a failure that was waiting
     * for the network, if play is still requested, through the next rung of the ladder.
     *
     * Safe to call from any thread, and a no-op after [detach].
     */
    public fun onNetworkArrival() {
        engine.onNetworkArrival()
    }

    /**
     * Runs [block] with the same-id transition rule raised: a PLAYLIST_CHANGED transition
     * that keeps the current media id, reported while [block] runs, is not read as a new
     * selection. Wrap every in-place `replaceMediaItem` the host makes for the item that is
     * playing (a metadata or artwork refresh). Choosing an item with `setMediaItems` must
     * not be wrapped: choosing the item already playing is a real restart.
     */
    public fun <T> runHostReplace(block: () -> T): T {
        checkThread()
        return engine.runHostReplace(block)
    }

    /**
     * Cancels the pending rung, both watchdogs and the stable-playback reset, and forgets a
     * pending network resume. For a host that stops playback on purpose (a sleep timer
     * expiry): call it, then pause, while [RecoveryHost.automaticStartAllowed] answers
     * false so nothing restarts.
     */
    public fun cancelAutomaticWork() {
        checkThread()
        engine.cancelAutomaticWork()
    }

    /**
     * Stops recovery for good: removes [listener] if [attach] registered it and cancels
     * every rung, watchdog and pending network handoff. Idempotent. Call it before the
     * player is released.
     */
    public fun detach() {
        checkThread()
        if (engine.isDetached) return
        engine.detach()
        if (registeredListener) player.removeListener(listener)
    }

    private fun checkThread() {
        check(Looper.myLooper() == player.applicationLooper) {
            "PlaybackRecovery must be used on the player's application looper"
        }
    }

    public companion object {
        /**
         * Attaches recovery to [player]. Must be called on the player's application looper.
         *
         * @param registerListener register [listener] on the player; pass false to forward
         *   callbacks from the host's own listener instead.
         * @param clock a monotonic clock in milliseconds; elapsed realtime by default.
         * @param dispatcher where delayed work runs; it must run on the player's
         *   application looper. Defaults to a dispatcher posting to that looper.
         */
        @JvmStatic
        @JvmOverloads
        public fun attach(
            player: Player,
            host: RecoveryHost,
            config: RecoveryConfig = RecoveryConfig(),
            registerListener: Boolean = true,
            clock: () -> Long = { SystemClock.elapsedRealtime() },
            dispatcher: CoroutineDispatcher = Handler(player.applicationLooper).asCoroutineDispatcher(),
        ): PlaybackRecovery {
            check(Looper.myLooper() == player.applicationLooper) {
                "PlaybackRecovery.attach must be called on the player's application looper"
            }
            val engine = RecoveryEngine(
                player = Media3RecoveryPlayer(player),
                host = host,
                config = config,
                parentScope = CoroutineScope(SupervisorJob() + dispatcher),
                clock = clock,
            )
            val recovery = PlaybackRecovery(player, engine, registerListener)
            if (registerListener) player.addListener(recovery.listener)
            // Registering a listener replays nothing: read what the player already holds.
            engine.onAttached()
            return recovery
        }
    }
}

/** [RecoveryPlayer] over a Media3 [Player]. */
internal class Media3RecoveryPlayer(private val player: Player) : RecoveryPlayer {
    override val playbackState: Int get() = player.playbackState
    override val playWhenReady: Boolean get() = player.playWhenReady
    override val isPlaying: Boolean get() = player.isPlaying
    override val playbackSuppressionReason: Int get() = player.playbackSuppressionReason
    override val hasPlayerError: Boolean get() = player.playerError != null
    override val mediaItemCount: Int get() = player.mediaItemCount
    override val currentMediaItemIndex: Int get() = player.currentMediaItemIndex
    override val currentMediaId: String? get() = player.currentMediaItem?.mediaId
    override val currentUri: String? get() = player.currentMediaItem?.localConfiguration?.uri?.toString()
    override val currentPosition: Long get() = player.currentPosition

    private val window = Timeline.Window()

    /**
     * The window uid: every `setMediaItems` entry gets a new one, applied at once. Read it
     * fresh each time and compare by value: a concatenated timeline allocates an equal pair
     * for a nested window on every read, so identity would read an unchanged item as new.
     */
    override val currentItemToken: Any?
        get() {
            val timeline = player.currentTimeline
            val index = player.currentMediaItemIndex
            if (timeline.isEmpty || index !in 0 until timeline.windowCount) return null
            return timeline.getWindow(index, window).uid
        }

    override fun mediaIdAt(index: Int): String? = player.getMediaItemAt(index).mediaId

    override fun uriAt(index: Int): String? = player.getMediaItemAt(index).localConfiguration?.uri?.toString()

    override fun stop() = player.stop()

    override fun seekTo(index: Int, positionMs: Long) = player.seekTo(index, positionMs)

    override fun prepare() = player.prepare()

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun replaceUri(index: Int, uri: String) {
        val item = player.getMediaItemAt(index)
        player.replaceMediaItem(index, item.buildUpon().setUri(uri).build())
    }
}
