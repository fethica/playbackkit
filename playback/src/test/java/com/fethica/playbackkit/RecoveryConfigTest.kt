package com.fethica.playbackkit

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryConfigTest {

    @Test fun `the defaults are the tested timings`() {
        val config = RecoveryConfig()
        assertEquals(15_000L, config.bufferingTimeoutMs)
        assertEquals(5_000L, config.stallCheckIntervalMs)
        assertEquals(250L, config.stallToleranceMs)
        assertEquals(15_000L, config.stallTimeoutMs)
        assertEquals(30_000L, config.stablePlaybackMs)
        assertEquals(1_000L, config.baseDelayMs)
        assertEquals(30_000L, config.maxDelayMs)
        assertEquals(6, config.failureReportThreshold)
        assertEquals(3, config.maxFiniteRecoveryAttempts)
        assertEquals(1_000L, config.suppressionPollMs)
    }

    /**
     * The policy, pinned to literals. The differential traces share [RecoveryPolicy] with
     * the reference, so they cannot see a change to it; these assertions can.
     */
    @Test fun `the policy constants are the tested values`() {
        assertEquals(1_000L, RecoveryPolicy.BASE_DELAY_MS)
        assertEquals(30_000L, RecoveryPolicy.MAX_DELAY_MS)
        assertEquals(30_000L, RecoveryPolicy.STABLE_PLAYBACK_MS)
        assertEquals(3, RecoveryPolicy.MAX_FINITE_RECOVERY_ATTEMPTS)
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            (1..8).map { RecoveryPolicy.retryDelayMs(it) },
        )
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            (1..8).map { RecoveryPolicy.retryDelayMs(it, RecoveryConfig().baseDelayMs, RecoveryConfig().maxDelayMs) },
        )
        assertEquals(false, RecoveryPolicy.shouldResetLadder(29_999L))
        assertEquals(true, RecoveryPolicy.shouldResetLadder(30_000L))
        assertEquals(
            listOf(true, true, true, false),
            listOf(1, 2, 3, 4).map {
                RecoveryPolicy.finiteRecoveryActionFor(permanent = false, attempts = it, hasNetwork = true) ==
                    FiniteRecoveryAction.RETRY
            },
        )
        assertEquals(false, RecoveryPolicy.shouldReportPlaybackFailure(5, 6, alreadyReported = false))
        assertEquals(true, RecoveryPolicy.shouldReportPlaybackFailure(6, 6, alreadyReported = false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a ceiling below the first rung is rejected`() {
        RecoveryConfig(baseDelayMs = 2_000L, maxDelayMs = 1_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero threshold is rejected`() {
        RecoveryConfig(failureReportThreshold = 0)
    }

    @Test fun `a custom backoff doubles to its own ceiling without overflow`() {
        assertEquals(500L, RecoveryPolicy.retryDelayMs(1, baseDelayMs = 500L, maxDelayMs = 4_000L))
        assertEquals(1_000L, RecoveryPolicy.retryDelayMs(2, baseDelayMs = 500L, maxDelayMs = 4_000L))
        assertEquals(4_000L, RecoveryPolicy.retryDelayMs(4, baseDelayMs = 500L, maxDelayMs = 4_000L))
        assertEquals(4_000L, RecoveryPolicy.retryDelayMs(9, baseDelayMs = 500L, maxDelayMs = 4_000L))
        assertEquals(3L shl 29, RecoveryPolicy.retryDelayMs(30, baseDelayMs = 3L, maxDelayMs = Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, RecoveryPolicy.retryDelayMs(40, baseDelayMs = 3L, maxDelayMs = Long.MAX_VALUE))
    }
}
