package com.fethica.playbackkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the outer recovery loop that sits above Media3's own per-load retries: backoff,
 * idle and pause guards, suppression, failure counting, permanent classification, the
 * live and finite actions, and the same-id transition rule.
 */
class RecoveryPolicyTest {

    @Test fun `the recovery's own stop does not re-enter the ladder`() =
        assertFalse(
            RecoveryPolicy.shouldRecoverFromIdle(
                playWhenReady = true,
                recoveryStopInProgress = true,
                errorReported = false
            )
        )

    @Test fun `an idle that carries a player error is left to onPlayerError`() =
        assertFalse(
            RecoveryPolicy.shouldRecoverFromIdle(
                playWhenReady = true,
                recoveryStopInProgress = false,
                errorReported = true
            )
        )

    @Test fun `an idle with play still requested and no other cause is a failure`() =
        assertTrue(
            RecoveryPolicy.shouldRecoverFromIdle(
                playWhenReady = true,
                recoveryStopInProgress = false,
                errorReported = false
            )
        )

    @Test fun `an idle after the listener paused is not a failure`() =
        assertFalse(
            RecoveryPolicy.shouldRecoverFromIdle(
                playWhenReady = false,
                recoveryStopInProgress = false,
                errorReported = false
            )
        )

    @Test fun `a pause the listener asked for cancels the pending recovery`() {
        assertTrue(RecoveryPolicy.shouldCancelRecoveryOnPlayWhenReady(playWhenReady = false, reason = RecoveryPolicy.PLAY_WHEN_READY_REASON_USER_REQUEST))
        assertTrue(RecoveryPolicy.shouldCancelRecoveryOnPlayWhenReady(playWhenReady = false, reason = RecoveryPolicy.PLAY_WHEN_READY_REASON_REMOTE))
        assertTrue(RecoveryPolicy.shouldCancelRecoveryOnPlayWhenReady(playWhenReady = false, reason = RecoveryPolicy.PLAY_WHEN_READY_REASON_AUDIO_BECOMING_NOISY))
    }

    @Test fun `a permanent focus loss cancels the pending recovery`() =
        assertTrue(RecoveryPolicy.shouldCancelRecoveryOnPlayWhenReady(playWhenReady = false, reason = RecoveryPolicy.PLAY_WHEN_READY_REASON_AUDIO_FOCUS_LOSS))

    @Test fun `recovery stands down while playback is only suppressed`() {
        assertTrue(RecoveryPolicy.shouldDeferRecovery(playbackSuppressionReason = 1))
        assertTrue(RecoveryPolicy.shouldDeferRecovery(playbackSuppressionReason = 2))
    }

    @Test fun `recovery runs normally when nothing is suppressing playback`() =
        assertFalse(
            RecoveryPolicy.shouldDeferRecovery(
                playbackSuppressionReason = RecoveryPolicy.PLAYBACK_SUPPRESSION_REASON_NONE
            )
        )

    @Test fun `resuming never cancels a recovery`() =
        assertFalse(RecoveryPolicy.shouldCancelRecoveryOnPlayWhenReady(playWhenReady = true, reason = RecoveryPolicy.PLAY_WHEN_READY_REASON_USER_REQUEST))

    @Test fun `a pending recovery does not run against a paused player`() =
        assertFalse(RecoveryPolicy.shouldRunPendingRecovery(playWhenReady = false))

    @Test fun `a pending recovery runs while playback is still wanted`() =
        assertTrue(RecoveryPolicy.shouldRunPendingRecovery(playWhenReady = true))

    @Test fun `a resume after a network gap is not an item failure`() =
        assertFalse(
            RecoveryPolicy.countsAsPlaybackFailure(RecoveryPolicy.REASON_NETWORK_RESTORED)
        )

    @Test fun `a live item reaching its end is not counted as a failure`() =
        assertFalse(
            RecoveryPolicy.countsAsPlaybackFailure(RecoveryPolicy.REASON_STATE_ENDED)
        )

    @Test fun `every other reason is still an item failure`() {
        listOf(
            RecoveryPolicy.REASON_BUFFER_TIMEOUT,
            RecoveryPolicy.REASON_STATE_IDLE,
            "stall_ready",
            "ready_not_playing",
            "ERROR_CODE_IO_BAD_HTTP_STATUS:404"
        ).forEach { reason ->
            assertTrue(reason, RecoveryPolicy.countsAsPlaybackFailure(reason))
        }
    }

    @Test fun `first retry waits one second`() =
        assertEquals(1_000L, RecoveryPolicy.retryDelayMs(attempt = 1))

    @Test fun `each attempt doubles the wait`() {
        assertEquals(2_000L, RecoveryPolicy.retryDelayMs(attempt = 2))
        assertEquals(4_000L, RecoveryPolicy.retryDelayMs(attempt = 3))
        assertEquals(8_000L, RecoveryPolicy.retryDelayMs(attempt = 4))
        assertEquals(16_000L, RecoveryPolicy.retryDelayMs(attempt = 5))
    }

    @Test fun `wait is capped at thirty seconds`() {
        assertEquals(30_000L, RecoveryPolicy.retryDelayMs(attempt = 6))
        assertEquals(30_000L, RecoveryPolicy.retryDelayMs(attempt = 40))
    }

    @Test fun `a very large attempt count does not overflow into a short wait`() =
        assertEquals(30_000L, RecoveryPolicy.retryDelayMs(attempt = 5_000))

    @Test fun `a momentary ready does not reset the ladder`() =
        assertFalse(RecoveryPolicy.shouldResetLadder(playedMs = 5_000L))

    @Test fun `sustained playback resets the ladder`() =
        assertTrue(RecoveryPolicy.shouldResetLadder(playedMs = 30_000L))

    @Test fun `no retry is scheduled while the device is offline`() =
        assertFalse(RecoveryPolicy.shouldScheduleRetry(hasNetwork = false, permanentFailure = false))

    @Test fun `no retry is scheduled for a permanently failed stream`() =
        assertFalse(RecoveryPolicy.shouldScheduleRetry(hasNetwork = true, permanentFailure = true))

    @Test fun `a retry is scheduled when online and the failure is transient`() =
        assertTrue(RecoveryPolicy.shouldScheduleRetry(hasNetwork = true, permanentFailure = false))

    @Test fun `a permanently failed live item stops instead of spinning`() =
        assertEquals(
            LiveRecoveryAction.STOP,
            RecoveryPolicy.liveRecoveryActionFor(permanent = true, hasNetwork = true)
        )

    @Test fun `an offline live item waits for a network arrival`() =
        assertEquals(
            LiveRecoveryAction.AWAIT_NETWORK,
            RecoveryPolicy.liveRecoveryActionFor(permanent = false, hasNetwork = false)
        )

    @Test fun `a permanent failure discovered offline still stops`() =
        assertEquals(
            LiveRecoveryAction.STOP,
            RecoveryPolicy.liveRecoveryActionFor(permanent = true, hasNetwork = false)
        )

    @Test fun `a transient live failure online climbs the ladder`() =
        assertEquals(
            LiveRecoveryAction.RETRY,
            RecoveryPolicy.liveRecoveryActionFor(permanent = false, hasNetwork = true)
        )

    @Test fun `a malformed container is permanent`() =
        assertTrue(RecoveryPolicy.isPermanentFailure(errorCode = 3001, httpStatus = null))

    @Test fun `an unsupported format is permanent`() =
        assertTrue(RecoveryPolicy.isPermanentFailure(errorCode = 3003, httpStatus = null))

    @Test fun `a missing file is permanent`() =
        assertTrue(RecoveryPolicy.isPermanentFailure(errorCode = 2005, httpStatus = null))

    @Test fun `a 404 is permanent`() =
        assertTrue(RecoveryPolicy.isPermanentFailure(errorCode = 2004, httpStatus = 404))

    @Test fun `a 500 is transient because the server may recover`() =
        assertFalse(RecoveryPolicy.isPermanentFailure(errorCode = 2004, httpStatus = 500))

    @Test fun `a 408 timeout is transient`() =
        assertFalse(RecoveryPolicy.isPermanentFailure(errorCode = 2004, httpStatus = 408))

    @Test fun `a 429 rate limit is transient`() =
        assertFalse(RecoveryPolicy.isPermanentFailure(errorCode = 2004, httpStatus = 429))

    @Test fun `a network connection failure is transient`() =
        assertFalse(RecoveryPolicy.isPermanentFailure(errorCode = 2001, httpStatus = null))

    @Test fun `a network timeout is transient`() =
        assertFalse(RecoveryPolicy.isPermanentFailure(errorCode = 2002, httpStatus = null))

    @Test fun `a metadata refresh of the same item is not a real transition`() =
        assertTrue(
            RecoveryPolicy.isMetadataOnlyTransition(
                reason = 3,
                previousMediaId = "live_3",
                newMediaId = "live_3"
            )
        )

    @Test fun `selecting another item while a retry is pending is a real transition`() =
        assertFalse(
            RecoveryPolicy.isMetadataOnlyTransition(
                reason = 3,
                previousMediaId = "live_3",
                newMediaId = "finite_1234"
            )
        )

    @Test fun `the first item of a fresh playlist is a real transition`() =
        assertFalse(
            RecoveryPolicy.isMetadataOnlyTransition(
                reason = 3,
                previousMediaId = null,
                newMediaId = "live_0"
            )
        )

    @Test fun `a cleared playlist is a real transition`() =
        assertFalse(
            RecoveryPolicy.isMetadataOnlyTransition(
                reason = 3,
                previousMediaId = "live_3",
                newMediaId = null
            )
        )

    @Test fun `an automatic advance is a real transition whatever the ids say`() {
        assertFalse(
            RecoveryPolicy.isMetadataOnlyTransition(
                reason = 1,
                previousMediaId = "live_3",
                newMediaId = "live_3"
            )
        )
        assertFalse(
            RecoveryPolicy.isMetadataOnlyTransition(
                reason = 0,
                previousMediaId = "live_3",
                newMediaId = "live_3"
            )
        )
    }

    @Test fun `the in-place replace the host just asked for is suppressed`() =
        assertTrue(
            RecoveryPolicy.shouldSuppressTransition(
                hostReplaceInProgress = true,
                reason = 3,
                previousMediaId = "live_3",
                newMediaId = "live_3"
            )
        )

    @Test fun `re-selecting the item already playing is a real restart`() =
        assertFalse(
            RecoveryPolicy.shouldSuppressTransition(
                hostReplaceInProgress = false,
                reason = 3,
                previousMediaId = "live_3",
                newMediaId = "live_3"
            )
        )

    @Test fun `a transition to another item is never suppressed`() =
        assertFalse(
            RecoveryPolicy.shouldSuppressTransition(
                hostReplaceInProgress = true,
                reason = 3,
                previousMediaId = "live_3",
                newMediaId = "finite_1234"
            )
        )

    @Test fun `a repeat an auto advance or a seek is never suppressed`() {
        listOf(0, 1, 2).forEach { reason ->
            assertFalse(
                "reason $reason",
                RecoveryPolicy.shouldSuppressTransition(
                    hostReplaceInProgress = true,
                    reason = reason,
                    previousMediaId = "live_3",
                    newMediaId = "live_3"
                )
            )
        }
    }

    @Test fun `a permanently failed finite item stops instead of retrying`() =
        assertEquals(
            FiniteRecoveryAction.STOP,
            RecoveryPolicy.finiteRecoveryActionFor(
                permanent = true, attempts = 1, hasNetwork = true
            )
        )

    @Test fun `an offline finite item waits for a network arrival`() =
        assertEquals(
            FiniteRecoveryAction.AWAIT_NETWORK,
            RecoveryPolicy.finiteRecoveryActionFor(
                permanent = false, attempts = 1, hasNetwork = false
            )
        )

    @Test fun `an offline finite item still waits once the budget looks spent`() =
        assertEquals(
            FiniteRecoveryAction.AWAIT_NETWORK,
            RecoveryPolicy.finiteRecoveryActionFor(
                permanent = false, attempts = 9, hasNetwork = false
            )
        )

    @Test fun `a transient finite failure is retried up to three times`() {
        (1..3).forEach { attempt ->
            assertEquals(
                "attempt $attempt",
                FiniteRecoveryAction.RETRY,
                RecoveryPolicy.finiteRecoveryActionFor(
                    permanent = false, attempts = attempt, hasNetwork = true
                )
            )
        }
    }

    @Test fun `a fourth attempt gives up rather than looping forever`() =
        assertEquals(
            FiniteRecoveryAction.STOP,
            RecoveryPolicy.finiteRecoveryActionFor(
                permanent = false, attempts = 4, hasNetwork = true
            )
        )

    @Test fun `a slow download is not a finite failure`() =
        assertFalse(
            RecoveryPolicy.finiteRecoveryAppliesTo(RecoveryPolicy.REASON_BUFFER_TIMEOUT)
        )

    @Test fun `a frozen position is a finite failure`() =
        assertTrue(RecoveryPolicy.finiteRecoveryAppliesTo("stall_ready"))

    @Test fun `a player error is a finite failure`() =
        assertTrue(RecoveryPolicy.finiteRecoveryAppliesTo("ERROR_CODE_IO_BAD_HTTP_STATUS:404"))

    @Test fun `the end of a finite item is not a failure`() =
        assertFalse(
            RecoveryPolicy.finiteRecoveryAppliesTo(RecoveryPolicy.REASON_STATE_ENDED)
        )

    @Test fun `idle is not a failure the recovery should answer`() =
        assertFalse(
            RecoveryPolicy.finiteRecoveryAppliesTo(RecoveryPolicy.REASON_STATE_IDLE)
        )

    @Test fun `finite recovery reuses the live backoff`() {
        assertEquals(1_000L, RecoveryPolicy.retryDelayMs(attempt = 1))
        assertEquals(4_000L, RecoveryPolicy.retryDelayMs(attempt = 3))
    }

    @Test fun `only a listener's seek re-arms the finite ladder`() {
        assertFalse(RecoveryPolicy.shouldResetFiniteLadderOnSeek(ladderInitiated = true))
        assertTrue(RecoveryPolicy.shouldResetFiniteLadderOnSeek(ladderInitiated = false))
    }
}
