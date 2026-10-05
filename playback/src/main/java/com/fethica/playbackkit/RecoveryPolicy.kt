package com.fethica.playbackkit

/** What a live item that failed to play should do next. */
public enum class LiveRecoveryAction {
    /** Give up: pause, report it, and leave the player stopped rather than spinning. */
    STOP,

    /** Re-prepare from the live edge after the usual backoff. */
    RETRY,

    /** Nothing to retry against; a network arrival resumes playback. */
    AWAIT_NETWORK,
}

/** What a finite item that failed to play should do next. */
public enum class FiniteRecoveryAction {
    /** Give up: report it, and leave the player stopped rather than looping. */
    STOP,

    /** Re-prepare at the retained position after the usual backoff. */
    RETRY,

    /** Nothing to retry against; a network arrival resumes playback. */
    AWAIT_NETWORK,
}

/**
 * The rules of the outer recovery loop, kept pure and free of Android types so every one
 * of them is unit-testable on the JVM.
 *
 * Media3 already retries each failed load itself (`DefaultLoadErrorHandlingPolicy`) and
 * only raises `onPlayerError` once those attempts are exhausted. These rules govern what
 * happens after that: how long to wait before re-preparing the player, when to give up,
 * and when to stand down. They deliberately do not duplicate the inner per-load ladder.
 *
 * Media3 constants (error codes, reasons, states) are inlined as plain Ints for the same
 * reason: no media3 type is needed to exercise a rule.
 */
public object RecoveryPolicy {
    /** The first rung of the backoff. */
    public const val BASE_DELAY_MS: Long = 1_000L

    /** The ceiling the backoff doubles towards. */
    public const val MAX_DELAY_MS: Long = 30_000L

    /**
     * How long playback must actually run before an item counts as recovered.
     *
     * Not "reached READY": a flapping stream reaches READY for an instant on every cycle,
     * so resetting there restarts the ladder forever and it never escalates.
     */
    public const val STABLE_PLAYBACK_MS: Long = 30_000L

    /**
     * Exponential backoff, [baseDelayMs] doubling to a [maxDelayMs] ceiling.
     *
     * @param attempt the rung about to be taken, counted from 1.
     */
    public fun retryDelayMs(
        attempt: Int,
        baseDelayMs: Long = BASE_DELAY_MS,
        maxDelayMs: Long = MAX_DELAY_MS,
    ): Long {
        if (attempt <= 1) return baseDelayMs.coerceAtMost(maxDelayMs)
        val doublings = attempt - 1
        // Bail to the ceiling before shifting: at 31+ doublings the shift would overflow
        // Long and wrap round to a short delay, recreating the storm the backoff prevents.
        if (doublings >= 31) return maxDelayMs
        // Compared before shifting for the same reason: base * 2^doublings > max exactly
        // when base > floor(max / 2^doublings).
        if (baseDelayMs > (maxDelayMs shr doublings)) return maxDelayMs
        return baseDelayMs shl doublings
    }

    /** True only after [stablePlaybackMs] of real playback, never on a READY blip. */
    public fun shouldResetLadder(playedMs: Long, stablePlaybackMs: Long = STABLE_PLAYBACK_MS): Boolean =
        playedMs >= stablePlaybackMs

    /** `Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED`. */
    public const val TRANSITION_REASON_PLAYLIST_CHANGED: Int = 3

    /**
     * Whether a PLAYLIST_CHANGED transition could be only the item being swapped in place,
     * judged on the ids alone.
     *
     * `replaceMediaItem`, which is how a host refreshes the metadata of the item that is
     * playing, reports PLAYLIST_CHANGED for that same item, and so does `setMediaItems`,
     * which is how an item is actually chosen. The media id narrows it down but does not
     * settle it, which is why this is only ever half of [shouldSuppressTransition].
     */
    public fun isMetadataOnlyTransition(
        reason: Int,
        previousMediaId: String?,
        newMediaId: String?,
    ): Boolean {
        if (reason != TRANSITION_REASON_PLAYLIST_CHANGED) return false
        if (previousMediaId == null || newMediaId == null) return false
        return previousMediaId == newMediaId
    }

    /**
     * Whether a transition should be ignored entirely.
     *
     * The ids alone cannot answer this. Choosing the item that is already playing calls
     * `setMediaItems` and reports exactly the same same-id PLAYLIST_CHANGED as an in-place
     * metadata refresh, yet it is a real restart: the pending retry has to be cancelled
     * and the ladder reset. So the host says so instead of the rule guessing:
     * [hostReplaceInProgress] is raised only around the host's own `replaceMediaItem`.
     *
     * The id check stays as the second half: a transition to a different item is a real
     * change whatever the flag says.
     */
    public fun shouldSuppressTransition(
        hostReplaceInProgress: Boolean,
        reason: Int,
        previousMediaId: String?,
        newMediaId: String?,
    ): Boolean = hostReplaceInProgress && isMetadataOnlyTransition(reason, previousMediaId, newMediaId)

    /**
     * Offline retries are pure battery burn; a network arrival resumes playback instead.
     * Permanent failures (bad container, 404) never recover by being asked again.
     */
    public fun shouldScheduleRetry(hasNetwork: Boolean, permanentFailure: Boolean): Boolean =
        hasNetwork && !permanentFailure

    /**
     * What a failed live item should do.
     *
     * [shouldScheduleRetry] answers "no retry" for two opposite situations. Offline is
     * waited out with play intent left up, because the network arrival re-prepares as
     * soon as signal returns. A permanent failure has nothing coming, so it stops:
     * otherwise a live item whose URL answers 404 spins forever and never reaches the
     * failure threshold.
     *
     * Permanent is checked first on purpose: a 404 was answered by a reachable server,
     * so it is still a 404 when the network comes back.
     */
    public fun liveRecoveryActionFor(permanent: Boolean, hasNetwork: Boolean): LiveRecoveryAction = when {
        permanent -> LiveRecoveryAction.STOP
        shouldScheduleRetry(hasNetwork, permanentFailure = false) -> LiveRecoveryAction.RETRY
        else -> LiveRecoveryAction.AWAIT_NETWORK
    }

    /** `Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST`. */
    public const val PLAY_WHEN_READY_REASON_USER_REQUEST: Int = 1

    /** `Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS`. */
    public const val PLAY_WHEN_READY_REASON_AUDIO_FOCUS_LOSS: Int = 2

    /** `Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY`. */
    public const val PLAY_WHEN_READY_REASON_AUDIO_BECOMING_NOISY: Int = 3

    /** `Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE`. */
    public const val PLAY_WHEN_READY_REASON_REMOTE: Int = 4

    /**
     * Whether a STATE_IDLE with playWhenReady still set is a failure the live ladder
     * should answer to.
     *
     * Media3 flushes listener events synchronously from inside `stop()`, and `stop()`
     * leaves playWhenReady alone. So the recovery's own `stop()` would re-enter this branch
     * and schedule the next recovery before the re-prepare had started. On the error path
     * the IDLE that follows `onPlayerError` in the same flush would count a second strike.
     *
     * [recoveryStopInProgress] is raised only around the ladder's own `stop()`;
     * [errorReported] is `Player.playerError != null`.
     */
    public fun shouldRecoverFromIdle(
        playWhenReady: Boolean,
        recoveryStopInProgress: Boolean,
        errorReported: Boolean,
    ): Boolean = playWhenReady && !recoveryStopInProgress && !errorReported

    /**
     * Whether a playWhenReady change should drop the pending recovery.
     *
     * Every rung ends in `play()`. Left alive across a pause it resumes audio the listener
     * has just stopped. A pause the listener asked for (a button, a headset unplug, a
     * remote command) ends the recovery, and so does audio-focus loss: media3 reports that
     * one by clearing playWhenReady, and it is the permanent kind. The transient kind (a
     * call, a navigation prompt) never reaches this callback; media3 suppresses playback
     * with playWhenReady left true, which is [shouldDeferRecovery]'s job.
     */
    public fun shouldCancelRecoveryOnPlayWhenReady(playWhenReady: Boolean, reason: Int): Boolean =
        !playWhenReady && reason in PAUSE_REASONS_THAT_END_RECOVERY

    /**
     * Whether a recovery rung whose delay has elapsed may still run.
     *
     * Cancelling the pending rung on a pause is not enough: a fresh error raised after
     * the pause can schedule a new rung against a paused player, and that rung ends in
     * `play()`. Every scheduler already requires playWhenReady when it decides, so
     * re-reading it at execution time only ever drops a rung that has gone stale.
     */
    public fun shouldRunPendingRecovery(playWhenReady: Boolean): Boolean = playWhenReady

    private val PAUSE_REASONS_THAT_END_RECOVERY = setOf(
        PLAY_WHEN_READY_REASON_USER_REQUEST,
        PLAY_WHEN_READY_REASON_AUDIO_BECOMING_NOISY,
        PLAY_WHEN_READY_REASON_REMOTE,
        PLAY_WHEN_READY_REASON_AUDIO_FOCUS_LOSS,
    )

    /** `Player.PLAYBACK_SUPPRESSION_REASON_NONE`. */
    public const val PLAYBACK_SUPPRESSION_REASON_NONE: Int = 0

    /**
     * Whether every recovery mechanism must stand down for now.
     *
     * Media3 answers a transient focus loss (a call) by suppressing playback: the state
     * stays READY, playWhenReady stays true, and only `isPlaying` goes false, so the
     * position stops moving while everything a watchdog looks at says audio should be
     * flowing. Nothing is lost by waiting: when the suppression lifts, a stream that
     * really was stuck is still stuck.
     */
    public fun shouldDeferRecovery(playbackSuppressionReason: Int): Boolean =
        playbackSuppressionReason != PLAYBACK_SUPPRESSION_REASON_NONE

    /** How many times a finite item is re-prepared before it is left alone. */
    public const val MAX_FINITE_RECOVERY_ATTEMPTS: Int = 3

    /** The buffering watchdog. */
    public const val REASON_BUFFER_TIMEOUT: String = "buffer_timeout"

    /** STATE_ENDED, which for a finite item is simply its end. */
    public const val REASON_STATE_ENDED: String = "state_ended"

    /** STATE_IDLE while playWhenReady is still set. */
    public const val REASON_STATE_IDLE: String = "state_idle"

    /** A network arrival asking for playback back after a gap. */
    public const val REASON_NETWORK_RESTORED: String = "network_restored"

    /** READY with play requested, nothing suppressing it, and still not playing. */
    public const val REASON_READY_NOT_PLAYING: String = "ready_not_playing"

    /** READY and playing, with a position that has not moved for the stall timeout. */
    public const val REASON_STALL_READY: String = "stall_ready"

    /**
     * Whether a reason routed into the live ladder is a failure of the item, as opposed to
     * a re-prepare the recovery asks for on its own account.
     *
     * Separate from the backoff, which every re-prepare climbs: this is the count the
     * failure threshold is measured on. `network_restored` is a resume after a gap that
     * was already counted where it happened. `state_ended` is a live connection the server
     * closed: the reconnect either works or raises its own error, and that one counts.
     * Unknown reasons count too: under-reporting a real failure is the worse mistake.
     */
    public fun countsAsPlaybackFailure(reason: String): Boolean = when (reason) {
        REASON_NETWORK_RESTORED, REASON_STATE_ENDED -> false
        else -> true
    }

    /**
     * Whether a reason is a failure of a finite item at all.
     *
     * `buffer_timeout` is the one that differs from live: a live item silent for the
     * buffering timeout is broken, but a long file on a slow link is still downloading,
     * and re-preparing it does not make the link faster. Media3 keeps buffering on its
     * own and raises `onPlayerError` if the load really is dead. `state_ended` is the
     * normal end, and a finite item only reaches `state_idle` through an error already
     * handled or through the recovery's own `stop()`.
     */
    public fun finiteRecoveryAppliesTo(reason: String): Boolean = when (reason) {
        REASON_BUFFER_TIMEOUT, REASON_STATE_ENDED, REASON_STATE_IDLE -> false
        else -> true
    }

    /**
     * What to do about a finite item that failed.
     *
     * The live ladder cannot be reused: every rung ends in `seekTo(index, 0)`, which
     * throws away the position the listener came back for. Finite items get a bounded
     * rule that re-prepares at the retained position.
     *
     * Offline is checked before the attempt budget: waiting for the network costs nothing,
     * and a long gap must not spend the budget on the way through.
     *
     * @param attempts the recovery about to be made, counted from 1.
     */
    public fun finiteRecoveryActionFor(
        permanent: Boolean,
        attempts: Int,
        hasNetwork: Boolean,
        maxAttempts: Int = MAX_FINITE_RECOVERY_ATTEMPTS,
    ): FiniteRecoveryAction = when {
        permanent -> FiniteRecoveryAction.STOP
        !hasNetwork -> FiniteRecoveryAction.AWAIT_NETWORK
        attempts > maxAttempts -> FiniteRecoveryAction.STOP
        else -> FiniteRecoveryAction.RETRY
    }

    /**
     * Whether a SEEK discontinuity on the current finite item should re-arm its attempt
     * counter.
     *
     * Every finite rung ends in its own `seekTo(index, retainedPositionMs)`, which reports
     * the same discontinuity a listener's seek does. Resetting on that one would make
     * attempt 1 the only attempt there ever is. A seek the ladder did not make (a restart,
     * a scrub) has to re-arm it.
     */
    public fun shouldResetFiniteLadderOnSeek(ladderInitiated: Boolean): Boolean = !ladderInitiated

    // Media3 PlaybackException codes.
    private const val ERROR_IO_NETWORK_CONNECTION_FAILED = 2001
    private const val ERROR_IO_NETWORK_CONNECTION_TIMEOUT = 2002
    private const val ERROR_IO_BAD_HTTP_STATUS = 2004
    private const val ERROR_IO_FILE_NOT_FOUND = 2005
    private const val ERROR_PARSING_CONTAINER_MALFORMED = 3001
    private const val ERROR_PARSING_CONTAINER_UNSUPPORTED = 3003

    /**
     * Whether asking again could ever help.
     *
     * This separates "the server for this item is broken" from "the device lost signal".
     * Connection failures and timeouts are the ordinary mobile case and stay retryable.
     */
    public fun isPermanentFailure(errorCode: Int, httpStatus: Int?): Boolean = when (errorCode) {
        ERROR_PARSING_CONTAINER_MALFORMED,
        ERROR_PARSING_CONTAINER_UNSUPPORTED,
        ERROR_IO_FILE_NOT_FOUND -> true
        ERROR_IO_BAD_HTTP_STATUS -> isPermanentHttpStatus(httpStatus)
        ERROR_IO_NETWORK_CONNECTION_FAILED,
        ERROR_IO_NETWORK_CONNECTION_TIMEOUT -> false
        else -> false
    }

    /**
     * 4xx means the request itself is wrong, so repeating it is pointless, except 408 and
     * 429, which explicitly mean "try again later". 5xx is worth retrying.
     */
    private fun isPermanentHttpStatus(status: Int?): Boolean {
        if (status == null) return false
        if (status == 408 || status == 429) return false
        return status in 400..499
    }

    /**
     * Whether a failure should be reported now.
     *
     * The count is the rule for an item that keeps flapping: [threshold] failures before
     * one report, so a bad minute on mobile data is not an event. [terminal] is the case
     * the count cannot express: a permanently failed live item gets no further attempts,
     * so it would never reach the threshold. A failure the recovery has decided to stop on
     * is reported on the spot, still only once.
     */
    public fun shouldReportPlaybackFailure(
        retryCount: Int,
        threshold: Int,
        alreadyReported: Boolean,
        terminal: Boolean = false,
    ): Boolean = !alreadyReported && (terminal || retryCount >= threshold)
}
