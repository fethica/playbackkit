package com.fethica.playbackkit.trace

import com.fethica.playbackkit.DISCONTINUITY_REASON_SEEK
import com.fethica.playbackkit.FiniteRecoveryAction
import com.fethica.playbackkit.ItemKind
import com.fethica.playbackkit.LiveRecoveryAction
import com.fethica.playbackkit.PLAYBACK_STATE_ENDED
import com.fethica.playbackkit.PLAYBACK_STATE_IDLE
import com.fethica.playbackkit.RecoveryCallbacks
import com.fethica.playbackkit.RecoveryEngine
import com.fethica.playbackkit.RecoveryPlayer
import com.fethica.playbackkit.RecoveryPolicy
import com.fethica.playbackkit.STATE_BUFFERING
import com.fethica.playbackkit.STATE_READY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The reference implementation the differential traces compare [RecoveryEngine] against.
 *
 * A mechanical lift of the recovery code of the service this library's behaviour comes
 * from, kept as close to the original statement order as the port allows. Lifted ranges,
 * in the original service file at the pinned revision:
 *  - lines 3079-3462: the live retry entry and rung, the in-place URI swap, the finite
 *    recovery and rung, the terminal finite failure signal and its once-only report,
 *    `cancelRetry` and the suppression wait;
 *  - lines 3818-3960: the stable-playback reset, the network predicate, the
 *    network-arrival collector, and the buffering and stall watchdogs (with
 *    `cancelBufferingRecovery` and `cancelStallRecovery` just after);
 *  - lines 693-958: the recovery statements of the listener arms (item transition, player
 *    error, playback state, isPlaying, playWhenReady, position discontinuity);
 *  - lines 378-386: the automatic-work cancellation a host stop performs.
 *
 * What was changed in the lift, and nothing else:
 *  - the player is [RecoveryPlayer]; `currentMediaItem?.localConfiguration?.uri` is
 *    `currentUri`, and the URI swap's `replaceMediaItem(buildUpon().setUri())` is
 *    `replaceUri`;
 *  - logging is removed; analytics calls and the terminal signal write lines to [log];
 *  - product statements that do not touch recovery state are removed (listen tracking,
 *    progress persistence, repeat mode, artwork grants, loudness, metadata clearing, the
 *    item's give-up restart record, autoplay and timer bookkeeping);
 *  - the timer settle is stubbed to "nothing due", the veto is the same flag read through
 *    the same pure predicate, the replacement-URI rule is the original pure rule over a
 *    scripted catalog, and the item kind and network come from the trace world;
 *  - the tracked-item check of the seek arm reads the id recorded by the transition arm,
 *    which is what the original tracker holds for a finite item.
 *
 * This class is never to be "fixed": a parity failure is a defect in the library.
 */
internal class ReferenceRecovery(
    private val port: RecoveryPlayer,
    private val serviceScope: CoroutineScope,
    private val clock: () -> Long,
    private val kindOf: (String?) -> ItemKind,
    private val network: () -> Boolean,
    private val catalogUriFor: (String?) -> String?,
    private val log: MutableList<String>,
) : RecoveryCallbacks {

    private val player: RecoveryPlayer? get() = port

    private var retryJob: Job? = null
    private var bufferingRecoveryJob: Job? = null
    private var stallRecoveryJob: Job? = null
    private var ladderResetJob: Job? = null
    private val awaitingNetwork = AtomicBoolean(false)
    private var retryCount = 0
    private var failureCount = 0
    var playbackFailureReported = false
        private set
    private var finiteRecoveryAttempts = 0
    var finiteFailureReported = false
        private set
    private var lastTransitionMediaId: String? = null
    private var refreshingMetadata = false
    private var recoveryStopInProgress = false
    private var ladderInitiatedSeek = false
    private var lastObservedPositionMs: Long = 0L
    private var lastPositionRealtimeMs: Long = 0L
    var sleepStopActive = false
    private var trackedFiniteId: String? = null

    private companion object {
        const val PLAYBACK_FAILURE_THRESHOLD = 6
        const val SUPPRESSION_POLL_MS = 1_000L
    }

    // Stubs for the seams.
    private fun settleDueSleepTimer(@Suppress("UNUSED_PARAMETER") manualPlay: Boolean): Any? = null
    private fun sleepAllowsAutomaticStart(sleepStopActive: Boolean): Boolean = !sleepStopActive
    private fun currentItemIsFinite(): Boolean = kindOf(player?.currentMediaId) == ItemKind.FINITE
    private fun hasNetwork(): Boolean = network()

    private class LiveRecovery(val retryUri: String?, val permanentFailure: Boolean)

    private fun liveRecoveryFor(permanentFailure: Boolean, queuedUri: String?, mediaId: String?): LiveRecovery {
        val fresh = catalogUriFor(mediaId)?.trim().orEmpty()
        val retryUri = fresh.takeIf { queuedUri != null && it.isNotEmpty() && it != queuedUri }
        return LiveRecovery(retryUri = retryUri, permanentFailure = permanentFailure && retryUri == null)
    }

    /** Lines 378-386, the recovery part of a host stop's automatic-work cancellation. */
    fun cancelAutomaticWork() {
        cancelRetry()
        cancelBufferingRecovery()
        cancelStallRecovery()
        cancelLadderReset()
        awaitingNetwork.set(false)
    }

    /** Lines 671-676, the bracket around a host's in-place replace. */
    fun <T> hostReplace(block: () -> T): T {
        refreshingMetadata = true
        try {
            return block()
        } finally {
            refreshingMetadata = false
        }
    }

    // region Listener arms, lines 693-958

    override fun onMediaItemTransition(mediaId: String?, reason: Int) {
        if (player == null) return
        val newMediaId = mediaId
        if (RecoveryPolicy.shouldSuppressTransition(
                refreshingMetadata, reason, lastTransitionMediaId, newMediaId
            )
        ) {
            return
        }
        lastTransitionMediaId = newMediaId
        trackedFiniteId = newMediaId?.takeIf { kindOf(it) == ItemKind.FINITE }
        cancelRetry()
        cancelLadderReset()
        awaitingNetwork.set(false)
        retryCount = 0
        failureCount = 0
        playbackFailureReported = false
        finiteRecoveryAttempts = 0
        finiteFailureReported = false
        ladderInitiatedSeek = false
    }

    override fun onPlayerError(errorCode: Int, errorCodeName: String, httpStatus: Int?) {
        val reason = if (httpStatus != null) "$errorCodeName:$httpStatus" else errorCodeName
        retryCurrentItem(
            reason,
            permanentFailure = RecoveryPolicy.isPermanentFailure(errorCode, httpStatus)
        )
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val exoPlayer = player
        when (playbackState) {
            STATE_READY -> {
                cancelBufferingRecovery()
                startStallRecovery()
                scheduleLadderReset()
            }
            STATE_BUFFERING -> scheduleBufferingRecovery()
            PLAYBACK_STATE_ENDED -> {
                cancelStallRecovery()
                retryCurrentItem(RecoveryPolicy.REASON_STATE_ENDED)
            }
            PLAYBACK_STATE_IDLE -> {
                cancelBufferingRecovery()
                cancelStallRecovery()
                val errorReported = exoPlayer?.hasPlayerError == true
                if (RecoveryPolicy.shouldRecoverFromIdle(
                        playWhenReady = exoPlayer?.playWhenReady == true,
                        recoveryStopInProgress = recoveryStopInProgress,
                        errorReported = errorReported
                    )
                ) {
                    retryCurrentItem(RecoveryPolicy.REASON_STATE_IDLE)
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying && settleDueSleepTimer(manualPlay = false) != null) return
        val exoPlayer = player
        if (!isPlaying &&
            exoPlayer?.playWhenReady == true &&
            exoPlayer.playbackState == STATE_READY &&
            exoPlayer.playbackSuppressionReason == RecoveryPolicy.PLAYBACK_SUPPRESSION_REASON_NONE
        ) {
            retryCurrentItem("ready_not_playing")
            return
        }
        if (!isPlaying && sleepAllowsAutomaticStart(sleepStopActive)) {
            startStallRecovery()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) {
            sleepStopActive = false
            settleDueSleepTimer(manualPlay = true)
        }
        if (playWhenReady) startStallRecovery() else cancelStallRecovery()
        if (RecoveryPolicy.shouldCancelRecoveryOnPlayWhenReady(playWhenReady, reason) &&
            retryJob?.isActive == true
        ) {
            cancelRetry()
        }
    }

    override fun onPositionDiscontinuity(reason: Int) {
        if (reason != DISCONTINUITY_REASON_SEEK) return
        val currentFiniteId = player?.currentMediaId?.takeIf { kindOf(it) == ItemKind.FINITE } ?: return
        if (currentFiniteId != trackedFiniteId) return
        cancelRetry()
        cancelLadderReset()
        if (RecoveryPolicy.shouldResetFiniteLadderOnSeek(ladderInitiatedSeek)) {
            finiteRecoveryAttempts = 0
        }
    }

    // endregion

    // region Lines 3079-3462

    private fun retryCurrentItem(
        reason: String,
        permanentFailure: Boolean = false
    ) {
        settleDueSleepTimer(manualPlay = false)
        if (!sleepAllowsAutomaticStart(sleepStopActive)) {
            return
        }
        val exoPlayer = player ?: return
        if (exoPlayer.mediaItemCount <= 0) return

        if (currentItemIsFinite()) {
            if (RecoveryPolicy.finiteRecoveryAppliesTo(reason)) {
                recoverFinite(reason, permanentFailure)
            }
            return
        }

        cancelLadderReset()
        retryCount += 1
        if (RecoveryPolicy.countsAsPlaybackFailure(reason)) failureCount += 1

        val recovery = liveRecoveryFor(
            permanentFailure,
            exoPlayer.currentUri,
            exoPlayer.currentMediaId
        )
        val action = RecoveryPolicy.liveRecoveryActionFor(recovery.permanentFailure, hasNetwork())
        if (RecoveryPolicy.shouldReportPlaybackFailure(
                failureCount,
                PLAYBACK_FAILURE_THRESHOLD,
                playbackFailureReported,
                terminal = action == LiveRecoveryAction.STOP
            )
        ) {
            playbackFailureReported = true
            log += "t=${clock()} REPORT live reason=$reason retries=$retryCount"
        }

        when (action) {
            LiveRecoveryAction.AWAIT_NETWORK -> {
                cancelRetry()
                awaitingNetwork.set(true)
                return
            }
            LiveRecoveryAction.STOP -> {
                cancelRetry()
                awaitingNetwork.set(false)
                exoPlayer.pause()
                exoPlayer.stop()
                return
            }
            LiveRecoveryAction.RETRY -> Unit
        }

        val delayMs = RecoveryPolicy.retryDelayMs(retryCount)
        val scheduledMediaId = exoPlayer.currentMediaId

        cancelRetry()
        retryJob = serviceScope.launch {
            delay(delayMs)
            awaitPlaybackUnsuppressed()
            settleDueSleepTimer(manualPlay = false)
            if (!sleepAllowsAutomaticStart(sleepStopActive)) {
                return@launch
            }
            val activePlayer = player ?: return@launch
            if (activePlayer.mediaItemCount <= 0) return@launch
            if (activePlayer.currentMediaId != scheduledMediaId) {
                return@launch
            }
            if (!RecoveryPolicy.shouldRunPendingRecovery(activePlayer.playWhenReady)) {
                return@launch
            }
            val index = activePlayer.currentMediaItemIndex.coerceIn(0, activePlayer.mediaItemCount - 1)
            recoveryStopInProgress = true
            try {
                activePlayer.stop()
            } finally {
                recoveryStopInProgress = false
            }
            swapToRefreshedUri(activePlayer, index)
            activePlayer.seekTo(index, 0)
            activePlayer.prepare()
            activePlayer.play()
        }
    }

    private fun swapToRefreshedUri(activePlayer: RecoveryPlayer, index: Int) {
        val retryUri = liveRecoveryFor(
            permanentFailure = false,
            queuedUri = activePlayer.uriAt(index),
            mediaId = activePlayer.mediaIdAt(index)
        ).retryUri ?: return
        refreshingMetadata = true
        try {
            activePlayer.replaceUri(index, retryUri)
        } finally {
            refreshingMetadata = false
        }
    }

    private fun recoverFinite(reason: String, permanentFailure: Boolean) {
        val exoPlayer = player ?: return
        cancelLadderReset()
        val attempt = finiteRecoveryAttempts + 1
        val online = hasNetwork()
        when (RecoveryPolicy.finiteRecoveryActionFor(permanentFailure, attempt, online)) {
            FiniteRecoveryAction.AWAIT_NETWORK -> {
                cancelRetry()
                awaitingNetwork.set(true)
            }
            FiniteRecoveryAction.STOP -> {
                cancelRetry()
                awaitingNetwork.set(false)
                exoPlayer.pause()
                exoPlayer.stop()
                reportFiniteFailure(
                    if (permanentFailure) reason else "$reason:exhausted"
                )
                publishFiniteFailure()
            }
            FiniteRecoveryAction.RETRY -> {
                finiteRecoveryAttempts = attempt
                awaitingNetwork.set(false)
                val index = exoPlayer.currentMediaItemIndex.coerceIn(0, exoPlayer.mediaItemCount - 1)
                val retainedPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                val scheduledMediaId = exoPlayer.currentMediaId
                val delayMs = RecoveryPolicy.retryDelayMs(attempt)
                cancelRetry()
                retryJob = serviceScope.launch {
                    delay(delayMs)
                    awaitPlaybackUnsuppressed()
                    settleDueSleepTimer(manualPlay = false)
                    if (!sleepAllowsAutomaticStart(sleepStopActive)) {
                        return@launch
                    }
                    val activePlayer = player ?: return@launch
                    if (activePlayer.mediaItemCount <= 0) return@launch
                    if (activePlayer.currentMediaId != scheduledMediaId) return@launch
                    if (!RecoveryPolicy.shouldRunPendingRecovery(activePlayer.playWhenReady)) {
                        return@launch
                    }
                    recoveryStopInProgress = true
                    try {
                        activePlayer.stop()
                    } finally {
                        recoveryStopInProgress = false
                    }
                    ladderInitiatedSeek = true
                    try {
                        activePlayer.seekTo(index, retainedPositionMs)
                        activePlayer.prepare()
                        activePlayer.play()
                    } finally {
                        ladderInitiatedSeek = false
                    }
                }
            }
        }
    }

    private fun publishFiniteFailure() {
        val id = player?.currentMediaId
        if (id.isNullOrBlank()) return
        log += "t=${clock()} UI finite failed id=$id"
    }

    private fun reportFiniteFailure(reason: String) {
        if (finiteFailureReported) return
        finiteFailureReported = true
        log += "t=${clock()} REPORT finite reason=$reason"
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    private suspend fun awaitPlaybackUnsuppressed() {
        while (
            RecoveryPolicy.shouldDeferRecovery(
                player?.playbackSuppressionReason ?: RecoveryPolicy.PLAYBACK_SUPPRESSION_REASON_NONE
            )
        ) {
            delay(SUPPRESSION_POLL_MS)
        }
    }

    // endregion

    // region Lines 3818-3970

    private fun scheduleLadderReset() {
        if (retryCount == 0 && !playbackFailureReported && finiteRecoveryAttempts == 0) return
        cancelLadderReset()
        ladderResetJob = serviceScope.launch {
            val startedAtMs = clock()
            delay(RecoveryPolicy.STABLE_PLAYBACK_MS)
            val activePlayer = player ?: return@launch
            val playedMs = clock() - startedAtMs
            if (activePlayer.isPlaying && RecoveryPolicy.shouldResetLadder(playedMs)) {
                retryCount = 0
                failureCount = 0
                finiteRecoveryAttempts = 0
            }
        }
    }

    private fun cancelLadderReset() {
        ladderResetJob?.cancel()
        ladderResetJob = null
    }

    /** The body of the arrival collector, lines 3868-3875, dispatched like a collected emission. */
    fun onNetworkArrival() {
        serviceScope.launch {
            if (!awaitingNetwork.getAndSet(false)) return@launch
            val activePlayer = player ?: return@launch
            if (activePlayer.playWhenReady && activePlayer.mediaItemCount > 0) {
                retryCurrentItem(RecoveryPolicy.REASON_NETWORK_RESTORED)
            }
        }
    }

    private fun scheduleBufferingRecovery() {
        cancelBufferingRecovery()
        bufferingRecoveryJob = serviceScope.launch {
            delay(15_000L)
            val exoPlayer = player ?: return@launch
            if (RecoveryPolicy.shouldDeferRecovery(exoPlayer.playbackSuppressionReason)) {
                scheduleBufferingRecovery()
                return@launch
            }
            if (exoPlayer.playbackState == STATE_BUFFERING && exoPlayer.playWhenReady) {
                retryCurrentItem(RecoveryPolicy.REASON_BUFFER_TIMEOUT)
            }
        }
    }

    private fun startStallRecovery() {
        val exoPlayer = player ?: return
        lastObservedPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        lastPositionRealtimeMs = clock()
        scheduleStallCheck()
    }

    private fun scheduleStallCheck() {
        cancelStallRecovery()
        stallRecoveryJob = serviceScope.launch {
            delay(5_000L)
            val exoPlayer = player ?: return@launch
            if (!exoPlayer.playWhenReady || exoPlayer.playbackState != STATE_READY) return@launch
            if (RecoveryPolicy.shouldDeferRecovery(exoPlayer.playbackSuppressionReason)) {
                startStallRecovery()
                return@launch
            }

            val nowRealtime = clock()
            val currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            if (currentPosition > lastObservedPositionMs + 250L) {
                lastObservedPositionMs = currentPosition
                lastPositionRealtimeMs = nowRealtime
                scheduleStallCheck()
                return@launch
            }

            if (nowRealtime - lastPositionRealtimeMs >= 15_000L) {
                retryCurrentItem("stall_ready")
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

    // endregion

    fun snapshot(): RecoveryEngine.Snapshot = RecoveryEngine.Snapshot(
        retryCount = retryCount,
        failureCount = failureCount,
        finiteAttempts = finiteRecoveryAttempts,
        awaitingNetwork = awaitingNetwork.get(),
        retryPending = retryJob?.isActive == true,
        bufferingArmed = bufferingRecoveryJob?.isActive == true,
        stallArmed = stallRecoveryJob?.isActive == true,
        ladderResetArmed = ladderResetJob?.isActive == true,
    )
}
