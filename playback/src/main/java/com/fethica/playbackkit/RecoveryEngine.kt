package com.fethica.playbackkit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The recovery logic, written against [RecoveryPlayer] so it runs on the JVM.
 *
 * Owns the live retry ladder, the finite recovery at the retained position, the buffering
 * and stall watchdogs, suppression-aware waiting, the network-arrival handoff and the
 * stable-playback reset. Every member except [onNetworkArrival] runs on the player's
 * looper; [scope] dispatches on that looper too.
 *
 * ## Events are delivered after the work that caused them
 *
 * Every entry point (a player callback, a delayed job, attachment) runs as one
 * transaction. [RecoveryEvent]s raised inside it are queued and handed to
 * [RecoveryHost.onEvent] only once the transaction has finished: the player commands it
 * decided on have been issued and the rung it scheduled is installed and cancellable. A
 * host reacting to an event therefore never runs between a decision and its action. A
 * host call recovery cannot avoid making in the middle ([RecoveryHost.onBeforeAutomaticStart],
 * [RecoveryHost.retrySourceFor]) is followed by a fresh check of the current item and
 * play intent before recovery acts on what it read before the call.
 */
internal class RecoveryEngine(
    private val player: RecoveryPlayer,
    private val host: RecoveryHost,
    private val config: RecoveryConfig,
    parentScope: CoroutineScope,
    private val clock: () -> Long,
) : RecoveryCallbacks {

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    private val detached = AtomicBoolean(false)

    private var retryJob: Job? = null
    private var bufferingRecoveryJob: Job? = null
    private var stallRecoveryJob: Job? = null
    private var ladderResetJob: Job? = null

    /**
     * Set when a failure found no network, so the next arrival knows to resume. Atomic
     * because an arrival can be signalled from any thread; `getAndSet` lets exactly one
     * arrival claim the pending resume.
     */
    private val awaitingNetwork = AtomicBoolean(false)

    /** Rungs of the live backoff: how long the next re-prepare waits. */
    private var retryCount = 0

    /** Live failures since the last stable playback or real transition. */
    private var failureCount = 0

    /**
     * Raised the first time a live failure crosses the threshold (or is terminal) in an
     * incident, cleared by a real transition only. Recovery does not report anything with
     * it; it keeps the stable-playback reset armed while an incident is open.
     */
    private var failureIncidentOpen = false

    /** Finite recovery is bounded separately: it re-prepares, it does not climb. */
    private var finiteRecoveryAttempts = 0

    /** The media id of the last real transition, so an in-place replace is recognisable. */
    private var lastTransitionMediaId: String? = null

    /** Raised only around a host's in-place replace and recovery's own URI swap. */
    private var hostReplaceInProgress = false

    /** Raised only around the ladder's own `stop()`. */
    private var recoveryStopInProgress = false

    /** Raised only around a finite rung's own `seekTo`. */
    private var ladderInitiatedSeek = false

    private var lastObservedPositionMs = 0L
    private var lastPositionRealtimeMs = 0L

    /** Events raised by the running transaction, delivered when it finishes. */
    private val pendingEvents = ArrayDeque<RecoveryEvent>()
    private var transactionDepth = 0
    private var deliveringEvents = false

    /** Playback-intent generation; informational, see [RecoveryEvent]. */
    var generation: Long = 0L
        private set

    val isDetached: Boolean get() = detached.get()

    // region Host entry points

    /** Thread-safe: hops onto the looper, tracked by [job], a no-op after [detach]. */
    fun onNetworkArrival() {
        if (detached.get()) return
        scope.launch {
            if (detached.get()) return@launch
            if (!awaitingNetwork.getAndSet(false)) return@launch
            transaction<Unit> {
                if (player.playWhenReady && player.mediaItemCount > 0) {
                    retryCurrentItem(RecoveryPolicy.REASON_NETWORK_RESTORED)
                }
            }
        }
    }

    fun <T> runHostReplace(block: () -> T): T {
        val previous = hostReplaceInProgress
        hostReplaceInProgress = true
        try {
            return block()
        } finally {
            hostReplaceInProgress = previous
        }
    }

    fun cancelAutomaticWork() {
        cancelRetry()
        cancelBufferingRecovery()
        cancelStallRecovery()
        cancelLadderReset()
        awaitingNetwork.set(false)
        generation += 1
    }

    /**
     * Takes over a player that may already hold a prepared item: records its current item
     * as the baseline a seek or an in-place replace is judged against, and arms the
     * watchdog its state calls for. Not a selection: nothing is reset, cancelled or
     * reported.
     */
    fun onAttached() {
        if (detached.get()) return
        lastTransitionMediaId = player.currentMediaId
        when (player.playbackState) {
            STATE_READY -> if (player.playWhenReady) startStallRecovery()
            STATE_BUFFERING -> scheduleBufferingRecovery()
        }
    }

    fun detach() {
        if (!detached.compareAndSet(false, true)) return
        retryJob = null
        bufferingRecoveryJob = null
        stallRecoveryJob = null
        ladderResetJob = null
        awaitingNetwork.set(false)
        generation += 1
        job.cancel()
    }

    // endregion

    // region Player callbacks

    override fun onMediaItemTransition(mediaId: String?, reason: Int) {
        transaction<Unit> {
            if (detached.get()) return
            if (RecoveryPolicy.shouldSuppressTransition(hostReplaceInProgress, reason, lastTransitionMediaId, mediaId)) {
                return
            }
            lastTransitionMediaId = mediaId
            // A real selection is a legitimate fresh start, unlike a READY blip.
            cancelRetry()
            cancelLadderReset()
            awaitingNetwork.set(false)
            retryCount = 0
            failureCount = 0
            failureIncidentOpen = false
            finiteRecoveryAttempts = 0
            // A real transition is never the ladder's own seek.
            ladderInitiatedSeek = false
            generation += 1
            emit(RecoveryEvent.LadderReset(mediaId, generation, LadderResetCause.ITEM_CHANGED))
        }
    }

    override fun onPlayerError(errorCode: Int, errorCodeName: String, httpStatus: Int?) {
        transaction<Unit> {
            if (detached.get()) return
            val reason = if (httpStatus != null) "$errorCodeName:$httpStatus" else errorCodeName
            retryCurrentItem(reason, permanentFailure = RecoveryPolicy.isPermanentFailure(errorCode, httpStatus))
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        transaction<Unit> {
            if (detached.get()) return
            when (playbackState) {
                STATE_READY -> {
                    // Deliberately does not reset the ladder: a flapping stream reaches READY
                    // for an instant on every cycle. Stable playback resets it instead.
                    cancelBufferingRecovery()
                    startStallRecovery()
                    scheduleLadderReset()
                }
                STATE_BUFFERING -> scheduleBufferingRecovery()
                PLAYBACK_STATE_ENDED -> {
                    cancelStallRecovery()
                    // The end of a finite item is not a failure; only a live item reaching
                    // ENDED is, and the policy routes the reason accordingly.
                    retryCurrentItem(RecoveryPolicy.REASON_STATE_ENDED)
                }
                PLAYBACK_STATE_IDLE -> {
                    cancelBufferingRecovery()
                    cancelStallRecovery()
                    if (RecoveryPolicy.shouldRecoverFromIdle(
                            playWhenReady = player.playWhenReady,
                            recoveryStopInProgress = recoveryStopInProgress,
                            errorReported = player.hasPlayerError,
                        )
                    ) {
                        retryCurrentItem(RecoveryPolicy.REASON_STATE_IDLE)
                    }
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        transaction<Unit> {
            if (detached.get()) return
            if (!isPlaying &&
                player.playWhenReady &&
                player.playbackState == STATE_READY &&
                player.playbackSuppressionReason == RecoveryPolicy.PLAYBACK_SUPPRESSION_REASON_NONE
            ) {
                retryCurrentItem(RecoveryPolicy.REASON_READY_NOT_PLAYING)
                return
            }
            // The host's veto also keeps the stall watchdog from coming back while a host stop
            // (a sleep timer's pause, for example) is still on the stack.
            if (!isPlaying && host.automaticStartAllowed()) {
                startStallRecovery()
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        transaction<Unit> {
            if (detached.get()) return
            if (playWhenReady) generation += 1
            if (playWhenReady) startStallRecovery() else cancelStallRecovery()
            val endsRecovery = RecoveryPolicy.shouldCancelRecoveryOnPlayWhenReady(playWhenReady, reason)
            if (endsRecovery) generation += 1
            if (endsRecovery && retryJob?.isActive == true) {
                // The pending rung would end in play() and resume what the listener paused.
                cancelRetry()
            }
        }
    }

    override fun onPositionDiscontinuity(reason: Int) {
        transaction<Unit> {
            if (detached.get()) return
            if (reason != DISCONTINUITY_REASON_SEEK) return
            val mediaId = player.currentMediaId ?: return
            if (host.itemKind(mediaId) != ItemKind.FINITE) return
            // A seek reported around a playlist change belongs to the item that is arriving.
            if (mediaId != lastTransitionMediaId) return
            // A pending rung was scheduled against the pre-seek position.
            cancelRetry()
            cancelLadderReset()
            // Report state and awaitingNetwork survive a seek. The attempt budget re-arms, but
            // only for a seek the ladder did not make: every finite rung ends in its own seek.
            if (RecoveryPolicy.shouldResetFiniteLadderOnSeek(ladderInitiatedSeek)) {
                finiteRecoveryAttempts = 0
                generation += 1
                emit(RecoveryEvent.LadderReset(mediaId, generation, LadderResetCause.USER_SEEK))
            }
        }
    }

    // endregion

    // region Recovery

    private fun retryCurrentItem(reason: String, permanentFailure: Boolean = false) {
        // A host stop is settled first, before anything below can count, report, re-arm
        // network waiting or schedule a rung, and an automatic start it vetoes is refused
        // at entry.
        host.onBeforeAutomaticStart()
        if (!host.automaticStartAllowed()) return
        if (detached.get()) return
        if (player.mediaItemCount <= 0) return

        if (host.itemKind(player.currentMediaId) == ItemKind.FINITE) {
            if (RecoveryPolicy.finiteRecoveryAppliesTo(reason)) {
                recoverFinite(reason, permanentFailure)
            }
            return
        }

        // A failure means playback is not stable, so a pending "recovered" timer is void.
        cancelLadderReset()
        // The backoff climbs for every re-prepare; the failure count is the narrower one.
        retryCount += 1
        if (RecoveryPolicy.countsAsPlaybackFailure(reason)) failureCount += 1

        val mediaId = player.currentMediaId
        val playWhenReady = player.playWhenReady
        val intent = generation
        val retryUri = freshRetryUri(mediaId, player.currentUri, permanentFailure)
        // The host answered from inside recovery's work and may have issued commands.
        if (!stillCurrent(mediaId, playWhenReady, intent)) return
        val permanent = permanentFailure && retryUri == null
        val action = RecoveryPolicy.liveRecoveryActionFor(permanent, host.hasNetwork())
        val terminal = action == LiveRecoveryAction.STOP
        if (RecoveryPolicy.shouldReportPlaybackFailure(
                failureCount,
                config.failureReportThreshold,
                alreadyReported = false,
                terminal = terminal,
            )
        ) {
            failureIncidentOpen = true
            emit(
                RecoveryEvent.FailureThresholdReached(
                    mediaId, generation, reason, retries = retryCount, failures = failureCount, terminal = terminal,
                ),
            )
        }

        when (action) {
            LiveRecoveryAction.AWAIT_NETWORK -> {
                cancelRetry()
                // playWhenReady is left alone: the arrival reads it to tell interrupted
                // playback from playback the listener stopped.
                awaitingNetwork.set(true)
                emit(RecoveryEvent.AwaitingNetwork(mediaId, generation, ItemKind.LIVE, reason))
                return
            }
            LiveRecoveryAction.STOP -> {
                cancelRetry()
                awaitingNetwork.set(false)
                // pause() before stop(): stop() alone leaves playWhenReady true, which a
                // controller reads as "still buffering" for a stream that is never coming.
                player.pause()
                player.stop()
                emit(RecoveryEvent.GaveUp(mediaId, generation, ItemKind.LIVE, reason, retryCount, permanent))
                return
            }
            LiveRecoveryAction.RETRY -> Unit
        }

        val delayMs = RecoveryPolicy.retryDelayMs(retryCount, config.baseDelayMs, config.maxDelayMs)
        // A rung is only ever valid for the item it was scheduled for.
        val scheduledMediaId = mediaId
        cancelRetry()
        // Installed before it is announced, so a host reacting to the event can cancel it.
        retryJob = scope.launch {
            delay(delayMs)
            awaitPlaybackUnsuppressed()
            transaction<Unit> {
                host.onBeforeAutomaticStart()
                // A reselection or a seek made inside that call has already cancelled this rung.
                if (!isActive) return@launch
                if (!host.automaticStartAllowed()) return@launch
                if (player.mediaItemCount <= 0) return@launch
                if (player.currentMediaId != scheduledMediaId) return@launch
                // The rung ends in play(); a pause since it was scheduled makes it stale.
                if (!RecoveryPolicy.shouldRunPendingRecovery(player.playWhenReady)) return@launch
                val index = player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
                recoveryStopInProgress = true
                try {
                    player.stop()
                } finally {
                    recoveryStopInProgress = false
                }
                // Asked again here because the answer can change during the backoff.
                val retryUri = freshRetryUri(player.mediaIdAt(index), player.uriAt(index), permanent = false)
                if (!isActive || !stillCurrent(scheduledMediaId, playWhenReady = true, intent = null)) return@launch
                if (retryUri != null) swapToRetrySource(index, retryUri)
                player.seekTo(index, 0)
                player.prepare()
                player.play()
            }
        }
        emit(RecoveryEvent.RungScheduled(mediaId, generation, ItemKind.LIVE, reason, retryCount, delayMs, 0L))
    }

    /**
     * Whether the item and play intent recovery read before a host call are still the
     * current ones. [intent] is the generation read before the call, or null to skip that
     * check; the generation alone is not enough, since a callback the call caused may
     * still be queued behind the one running now.
     */
    private fun stillCurrent(mediaId: String?, playWhenReady: Boolean, intent: Long?): Boolean =
        !detached.get() &&
            player.mediaItemCount > 0 &&
            player.currentMediaId == mediaId &&
            player.playWhenReady == playWhenReady &&
            (intent == null || generation == intent)

    /** The host's replacement URI, kept only when it really is a different one. */
    private fun freshRetryUri(mediaId: String?, currentUri: String?, permanent: Boolean): String? {
        val fresh = host.retrySourceFor(mediaId, currentUri, permanent)?.trim().orEmpty()
        return fresh.takeIf { currentUri != null && it.isNotEmpty() && it != currentUri }
    }

    /**
     * Points the failing item at the host's replacement URI right before a rung
     * re-prepares it. The swap keeps the media id, so it runs inside the same-id bracket.
     */
    private fun swapToRetrySource(index: Int, retryUri: String) {
        runHostReplace { player.replaceUri(index, retryUri) }
    }

    /** The finite equivalent of the live ladder: bounded, and at the retained position. */
    private fun recoverFinite(reason: String, permanentFailure: Boolean) {
        cancelLadderReset()
        val attempt = finiteRecoveryAttempts + 1
        val online = host.hasNetwork()
        val mediaId = player.currentMediaId
        when (RecoveryPolicy.finiteRecoveryActionFor(permanentFailure, attempt, online, config.maxFiniteRecoveryAttempts)) {
            FiniteRecoveryAction.AWAIT_NETWORK -> {
                cancelRetry()
                awaitingNetwork.set(true)
                emit(RecoveryEvent.AwaitingNetwork(mediaId, generation, ItemKind.FINITE, reason))
            }
            FiniteRecoveryAction.STOP -> {
                cancelRetry()
                awaitingNetwork.set(false)
                player.pause()
                player.stop()
                emit(
                    RecoveryEvent.GaveUp(
                        mediaId, generation, ItemKind.FINITE, reason, finiteRecoveryAttempts, permanentFailure,
                    ),
                )
            }
            FiniteRecoveryAction.RETRY -> {
                finiteRecoveryAttempts = attempt
                awaitingNetwork.set(false)
                val index = player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
                val retainedPositionMs = player.currentPosition.coerceAtLeast(0L)
                val scheduledMediaId = mediaId
                val delayMs = RecoveryPolicy.retryDelayMs(attempt, config.baseDelayMs, config.maxDelayMs)
                cancelRetry()
                // Installed before it is announced, so a host reacting to the event can cancel it.
                retryJob = scope.launch {
                    delay(delayMs)
                    awaitPlaybackUnsuppressed()
                    transaction<Unit> {
                        host.onBeforeAutomaticStart()
                        // A reselection or a seek made inside that call has already cancelled this rung.
                        if (!isActive) return@launch
                        if (!host.automaticStartAllowed()) return@launch
                        if (player.mediaItemCount <= 0) return@launch
                        if (player.currentMediaId != scheduledMediaId) return@launch
                        if (!RecoveryPolicy.shouldRunPendingRecovery(player.playWhenReady)) return@launch
                        recoveryStopInProgress = true
                        try {
                            player.stop()
                        } finally {
                            recoveryStopInProgress = false
                        }
                        ladderInitiatedSeek = true
                        try {
                            player.seekTo(index, retainedPositionMs)
                            player.prepare()
                            player.play()
                        } finally {
                            ladderInitiatedSeek = false
                        }
                        emit(RecoveryEvent.FiniteRecovered(scheduledMediaId, generation, attempt, retainedPositionMs))
                    }
                }
                emit(
                    RecoveryEvent.RungScheduled(
                        mediaId, generation, ItemKind.FINITE, reason, attempt, delayMs, retainedPositionMs,
                    ),
                )
            }
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    /**
     * Holds a due rung while playback is only suppressed. Every rung ends in `play()`;
     * fired during a call it would tear down a stream the platform merely paused. The wait
     * is bounded by the rung's own job: a transition, a pause or detach cancels it.
     */
    private suspend fun awaitPlaybackUnsuppressed() {
        var announced = false
        while (RecoveryPolicy.shouldDeferRecovery(player.playbackSuppressionReason)) {
            if (!announced) {
                transaction<Unit> { emit(RecoveryEvent.WaitingForSuppression(player.currentMediaId, generation)) }
                announced = true
            }
            delay(config.suppressionPollMs)
        }
    }

    /**
     * Clears the ladder when a wall-clock window started at READY ends with the player
     * playing. Scheduled on every READY, cancelled by the next failure, so a stream that
     * blips READY and drops again never gets its ladder back. Only the end is checked.
     */
    private fun scheduleLadderReset() {
        if (retryCount == 0 && !failureIncidentOpen && finiteRecoveryAttempts == 0) return
        cancelLadderReset()
        ladderResetJob = scope.launch {
            val startedAtMs = clock()
            delay(config.stablePlaybackMs)
            val playedMs = clock() - startedAtMs
            transaction<Unit> {
                if (player.isPlaying && RecoveryPolicy.shouldResetLadder(playedMs, config.stablePlaybackMs)) {
                    retryCount = 0
                    failureCount = 0
                    // The incident stays open: report state survives a stable stretch and is
                    // cleared by the next real transition, which is what makes it per incident.
                    finiteRecoveryAttempts = 0
                    emit(RecoveryEvent.LadderReset(player.currentMediaId, generation, LadderResetCause.STABLE_PLAYBACK))
                }
            }
        }
    }

    private fun cancelLadderReset() {
        ladderResetJob?.cancel()
        ladderResetJob = null
    }

    private fun scheduleBufferingRecovery() {
        cancelBufferingRecovery()
        bufferingRecoveryJob = scope.launch {
            delay(config.bufferingTimeoutMs)
            // Buffering through a call is not a stalled stream: start the timeout again.
            if (RecoveryPolicy.shouldDeferRecovery(player.playbackSuppressionReason)) {
                scheduleBufferingRecovery()
                return@launch
            }
            if (player.playbackState == STATE_BUFFERING && player.playWhenReady) {
                transaction<Unit> { retryCurrentItem(RecoveryPolicy.REASON_BUFFER_TIMEOUT) }
            }
        }
    }

    private fun startStallRecovery() {
        lastObservedPositionMs = player.currentPosition.coerceAtLeast(0L)
        lastPositionRealtimeMs = clock()
        scheduleStallCheck()
    }

    private fun scheduleStallCheck() {
        cancelStallRecovery()
        stallRecoveryJob = scope.launch {
            delay(config.stallCheckIntervalMs)
            if (!player.playWhenReady || player.playbackState != STATE_READY) return@launch
            // A call leaves READY and playWhenReady up and stops the position, exactly
            // what a dead stream looks like. Re-arm from the position as it is now, so no
            // part of the call counts toward the timeout.
            if (RecoveryPolicy.shouldDeferRecovery(player.playbackSuppressionReason)) {
                startStallRecovery()
                return@launch
            }
            val now = clock()
            val currentPosition = player.currentPosition.coerceAtLeast(0L)
            if (currentPosition > lastObservedPositionMs + config.stallToleranceMs) {
                lastObservedPositionMs = currentPosition
                lastPositionRealtimeMs = now
                scheduleStallCheck()
                return@launch
            }
            if (now - lastPositionRealtimeMs >= config.stallTimeoutMs) {
                transaction<Unit> { retryCurrentItem(RecoveryPolicy.REASON_STALL_READY) }
                return@launch
            }
            scheduleStallCheck()
        }
    }

    private fun cancelBufferingRecovery() {
        bufferingRecoveryJob?.cancel()
        bufferingRecoveryJob = null
    }

    private fun cancelStallRecovery() {
        stallRecoveryJob?.cancel()
        stallRecoveryJob = null
    }

    /** Queues [event] for delivery when the running transaction finishes. */
    private fun emit(event: RecoveryEvent) {
        pendingEvents.addLast(event)
        if (transactionDepth == 0) deliverEvents()
    }

    /**
     * Runs [block] as one transaction: events it raises are delivered only after it, and
     * every transaction nested inside it, has finished.
     */
    private inline fun <T> transaction(block: () -> T): T {
        transactionDepth += 1
        try {
            return block()
        } finally {
            transactionDepth -= 1
            if (transactionDepth == 0) deliverEvents()
        }
    }

    /**
     * Hands queued events to the host in order. A host command that re-enters recovery
     * during delivery runs its own transaction; its events join the end of the queue
     * instead of overtaking the ones still waiting.
     */
    private fun deliverEvents() {
        if (deliveringEvents) return
        deliveringEvents = true
        try {
            while (pendingEvents.isNotEmpty()) host.onEvent(pendingEvents.removeFirst())
        } finally {
            deliveringEvents = false
        }
    }

    // endregion

    /** Internal state, for tests. */
    internal fun snapshot(): Snapshot = Snapshot(
        retryCount = retryCount,
        failureCount = failureCount,
        finiteAttempts = finiteRecoveryAttempts,
        awaitingNetwork = awaitingNetwork.get(),
        retryPending = retryJob?.isActive == true,
        bufferingArmed = bufferingRecoveryJob?.isActive == true,
        stallArmed = stallRecoveryJob?.isActive == true,
        ladderResetArmed = ladderResetJob?.isActive == true,
    )

    internal data class Snapshot(
        val retryCount: Int,
        val failureCount: Int,
        val finiteAttempts: Int,
        val awaitingNetwork: Boolean,
        val retryPending: Boolean,
        val bufferingArmed: Boolean,
        val stallArmed: Boolean,
        val ladderResetArmed: Boolean,
    )
}
