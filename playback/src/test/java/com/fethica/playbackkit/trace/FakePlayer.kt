package com.fethica.playbackkit.trace

import com.fethica.playbackkit.DISCONTINUITY_REASON_SEEK
import com.fethica.playbackkit.PLAYBACK_STATE_ENDED
import com.fethica.playbackkit.PLAYBACK_STATE_IDLE
import com.fethica.playbackkit.RecoveryCallbacks
import com.fethica.playbackkit.RecoveryPlayer
import com.fethica.playbackkit.RecoveryPolicy
import com.fethica.playbackkit.STATE_BUFFERING
import com.fethica.playbackkit.STATE_READY

/** `Player.MEDIA_ITEM_TRANSITION_REASON_SEEK`. */
internal const val TRANSITION_REASON_SEEK = 2

/**
 * A scripted stand-in for a Media3 player, driven on virtual time.
 *
 * It keeps the parts of Media3's behaviour recovery depends on:
 *  - listener events are queued and flushed the way `ListenerSet` does it: synchronously
 *    from inside the command that caused them, except that a command issued from inside a
 *    listener callback only queues its events, which run after the current one finishes;
 *  - within one update, events go out in Media3's order (`ExoPlayerImpl.updatePlaybackInfo`
 *    in 1.9.2): position discontinuity, item transition, player error, playback state,
 *    playWhenReady, isPlaying;
 *  - a current-item replace comes in two shapes: [updateMetadata] is Media3's in-place
 *    update of an item whose URI is unchanged (the window keeps its identity and no item
 *    transition is reported), [replaceUri] is a source replace that reports a
 *    PLAYLIST_CHANGED transition for the same id unless [replaceReportsTransition] is off;
 *  - `play()` with playWhenReady already true reports nothing;
 *  - `stop()` moves to IDLE and keeps play intent and the last error; `prepare()` clears
 *    the error and moves IDLE to BUFFERING;
 *  - the position advances with the clock only while playing and not frozen.
 *
 * Every command is written to [log] with its virtual timestamp.
 */
internal class FakePlayer(
    private val clock: () -> Long,
    private val log: MutableList<String>,
) : RecoveryPlayer {

    class Item(val id: String, var uri: String)

    val items = mutableListOf<Item>()
    private var index = 0
    private var state = PLAYBACK_STATE_IDLE
    private var pwr = false
    private var suppression = RecoveryPolicy.PLAYBACK_SUPPRESSION_REASON_NONE
    private var errorCode: Int? = null
    private var basePositionMs = 0L
    private var accrueFromMs: Long? = null
    private var frozen = false

    /** Whether an in-place replace of the current item reports a PLAYLIST_CHANGED transition. */
    var replaceReportsTransition: Boolean = true

    val listeners = mutableListOf<RecoveryCallbacks>()

    private val queue = ArrayDeque<() -> Unit>()
    private var flushing = false
    private var holdingEvents = false
    private var hostDepth = 0

    override val playbackState: Int get() = state
    override val playWhenReady: Boolean get() = pwr
    override val isPlaying: Boolean get() = isPlayingNow()
    override val playbackSuppressionReason: Int get() = suppression
    override val hasPlayerError: Boolean get() = errorCode != null
    override val mediaItemCount: Int get() = items.size
    override val currentMediaItemIndex: Int get() = index
    override val currentMediaId: String? get() = items.getOrNull(index)?.id
    override val currentUri: String? get() = items.getOrNull(index)?.uri
    override val currentPosition: Long
        get() = basePositionMs + (accrueFromMs?.let { clock() - it } ?: 0L)

    /** The entry itself: `setMediaItems` makes a new one, a URI replace keeps it. */
    override val currentItemToken: Any? get() = items.getOrNull(index)

    override fun mediaIdAt(index: Int): String? = items[index].id
    override fun uriAt(index: Int): String? = items[index].uri

    private fun isPlayingNow() =
        pwr && state == STATE_READY && suppression == RecoveryPolicy.PLAYBACK_SUPPRESSION_REASON_NONE

    /** True while the trace itself drives the player, as the host app would. */
    var hostDriving: Boolean = false

    /**
     * Commands the host issues directly, or inside [asHost], are tagged `host`; commands
     * issued from a listener callback or from delayed work are recovery's own and tagged
     * `rec`.
     */
    private fun cmd(text: String) {
        val origin = if (hostDepth > 0 || (hostDriving && !flushing)) "host" else "rec"
        log += "t=${clock()} $origin $text"
    }

    /** Runs [block] as the host's own code, wherever it is called from (a listener, a timer). */
    fun <T> asHost(block: () -> T): T {
        hostDepth += 1
        try {
            return block()
        } finally {
            hostDepth -= 1
        }
    }

    /**
     * Runs [block] as if it were issued from inside a listener callback: the events of its
     * commands are queued, then delivered in order after it, reading the player as it is
     * by then.
     */
    fun inOneListenerTurn(block: () -> Unit) {
        holdingEvents = true
        try {
            block()
        } finally {
            holdingEvents = false
        }
        flush()
    }

    // region Commands recovery may issue

    override fun stop() {
        cmd("stop")
        update { if (state != PLAYBACK_STATE_IDLE) state = PLAYBACK_STATE_IDLE }
    }

    override fun seekTo(index: Int, positionMs: Long) {
        cmd("seekTo($index,$positionMs)")
        val transition = if (index != this.index) items[index].id to TRANSITION_REASON_SEEK else null
        update(transition = transition, discontinuity = DISCONTINUITY_REASON_SEEK) {
            this.index = index
            basePositionMs = positionMs
            if (state == STATE_READY || state == PLAYBACK_STATE_ENDED) state = STATE_BUFFERING
        }
    }

    override fun prepare() {
        cmd("prepare")
        update {
            if (state == PLAYBACK_STATE_IDLE) {
                errorCode = null
                state = if (items.isEmpty()) PLAYBACK_STATE_ENDED else STATE_BUFFERING
            }
        }
    }

    override fun play() {
        cmd("play")
        update(pwrReason = RecoveryPolicy.PLAY_WHEN_READY_REASON_USER_REQUEST) { pwr = true }
    }

    override fun pause() {
        cmd("pause")
        update(pwrReason = RecoveryPolicy.PLAY_WHEN_READY_REASON_USER_REQUEST) { pwr = false }
    }

    override fun replaceUri(index: Int, uri: String) {
        cmd("replaceUri($index,$uri)")
        items[index].uri = uri
        val transition = if (replaceReportsTransition && index == this.index) {
            items[index].id to RecoveryPolicy.TRANSITION_REASON_PLAYLIST_CHANGED
        } else {
            null
        }
        update(transition = transition) {}
    }

    // endregion

    // region Scripted world

    /**
     * `replaceMediaItem` with the URI unchanged, as a metadata or artwork refresh does:
     * Media3 updates the item in place and reports no item transition.
     */
    fun updateMetadata() {
        cmd("replaceMediaItem($index,metadata)")
        update {}
    }

    /** `setMediaItems([item])`: a PLAYLIST_CHANGED transition, even for the same id. */
    fun setItem(id: String, uri: String) {
        cmd("setMediaItems($id)")
        update(transition = id to RecoveryPolicy.TRANSITION_REASON_PLAYLIST_CHANGED) {
            items.clear()
            items += Item(id, uri)
            index = 0
            basePositionMs = 0L
            if (state == STATE_READY || state == PLAYBACK_STATE_ENDED) state = STATE_BUFFERING
        }
    }

    fun pauseFor(reason: Int) {
        cmd("pause(reason=$reason)")
        update(pwrReason = reason) { pwr = false }
    }

    fun userSeek(positionMs: Long) = seekTo(index, positionMs)

    fun fail(code: Int, httpStatus: Int?) {
        update(error = Triple(code, codeName(code), httpStatus)) {
            errorCode = code
            state = PLAYBACK_STATE_IDLE
        }
    }

    fun moveTo(newState: Int) = update { state = newState }

    fun suppress(on: Boolean) = update {
        suppression = if (on) 1 else RecoveryPolicy.PLAYBACK_SUPPRESSION_REASON_NONE
    }

    /** A dead stream that stays READY: the position stops while isPlaying stays true. */
    fun freeze(on: Boolean) = update { frozen = on }

    // endregion

    private fun update(
        transition: Pair<String?, Int>? = null,
        discontinuity: Int? = null,
        error: Triple<Int, String, Int?>? = null,
        pwrReason: Int = RecoveryPolicy.PLAY_WHEN_READY_REASON_USER_REQUEST,
        block: () -> Unit,
    ) {
        val oldState = state
        val oldPwr = pwr
        val oldPlaying = isPlayingNow()
        basePositionMs = currentPosition
        block()
        accrueFromMs = if (isPlayingNow() && !frozen) clock() else null
        val newState = state
        val newPwr = pwr
        val newPlaying = isPlayingNow()
        if (discontinuity != null) send { it.onPositionDiscontinuity(discontinuity) }
        if (transition != null) send { it.onMediaItemTransition(transition.first, transition.second) }
        if (error != null) send { it.onPlayerError(error.first, error.second, error.third) }
        if (newState != oldState) send { it.onPlaybackStateChanged(newState) }
        if (newPwr != oldPwr) send { it.onPlayWhenReadyChanged(newPwr, pwrReason) }
        if (newPlaying != oldPlaying) send { it.onIsPlayingChanged(newPlaying) }
        flush()
    }

    private fun send(event: (RecoveryCallbacks) -> Unit) {
        queue.addLast { listeners.toList().forEach(event) }
    }

    private fun flush() {
        if (flushing || holdingEvents) return
        flushing = true
        try {
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        } finally {
            flushing = false
        }
    }

    fun describe(): String =
        "player=${stateName(state)} pwr=$pwr playing=${isPlayingNow()} pos=$currentPosition " +
            "item=$currentMediaId uri=$currentUri err=$errorCode"

    companion object {
        fun codeName(code: Int): String = when (code) {
            2001 -> "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"
            2002 -> "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"
            2004 -> "ERROR_CODE_IO_BAD_HTTP_STATUS"
            2005 -> "ERROR_CODE_IO_FILE_NOT_FOUND"
            3001 -> "ERROR_CODE_PARSING_CONTAINER_MALFORMED"
            else -> "ERROR_CODE_$code"
        }

        fun stateName(state: Int): String = when (state) {
            PLAYBACK_STATE_IDLE -> "IDLE"
            STATE_BUFFERING -> "BUFFERING"
            STATE_READY -> "READY"
            PLAYBACK_STATE_ENDED -> "ENDED"
            else -> "?"
        }
    }
}
