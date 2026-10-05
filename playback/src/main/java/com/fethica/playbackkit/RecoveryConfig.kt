package com.fethica.playbackkit

/**
 * The timings and budgets of recovery. Every default is the behaviour the rules were
 * written and tested against.
 *
 * @param bufferingTimeoutMs how long STATE_BUFFERING may last, with play requested,
 *   before it counts as a failure. Live items only: a finite item that is merely slow is
 *   routed away by [RecoveryPolicy.finiteRecoveryAppliesTo].
 * @param stallCheckIntervalMs how often the position is sampled while READY.
 * @param stallToleranceMs how far the position must move between samples to count as
 *   progress.
 * @param stallTimeoutMs how long the position may stand still, while READY and playing,
 *   before it counts as a failure.
 * @param stablePlaybackMs how long playback must run before the ladder is cleared.
 * @param baseDelayMs the first rung of the backoff.
 * @param maxDelayMs the ceiling the backoff doubles towards.
 * @param failureReportThreshold how many live failures, since the last real item
 *   transition or stable stretch, before [RecoveryEvent.FailureThresholdReached] is
 *   emitted.
 * @param maxFiniteRecoveryAttempts how many times a finite item is re-prepared at its
 *   retained position before it is given up on.
 * @param suppressionPollMs how often a held-back rung checks whether playback suppression
 *   has lifted.
 */
public data class RecoveryConfig(
    val bufferingTimeoutMs: Long = 15_000L,
    val stallCheckIntervalMs: Long = 5_000L,
    val stallToleranceMs: Long = 250L,
    val stallTimeoutMs: Long = 15_000L,
    val stablePlaybackMs: Long = RecoveryPolicy.STABLE_PLAYBACK_MS,
    val baseDelayMs: Long = RecoveryPolicy.BASE_DELAY_MS,
    val maxDelayMs: Long = RecoveryPolicy.MAX_DELAY_MS,
    val failureReportThreshold: Int = 6,
    val maxFiniteRecoveryAttempts: Int = RecoveryPolicy.MAX_FINITE_RECOVERY_ATTEMPTS,
    val suppressionPollMs: Long = 1_000L,
) {
    init {
        require(bufferingTimeoutMs > 0) { "bufferingTimeoutMs must be positive" }
        require(stallCheckIntervalMs > 0) { "stallCheckIntervalMs must be positive" }
        require(stallToleranceMs >= 0) { "stallToleranceMs must not be negative" }
        require(stallTimeoutMs > 0) { "stallTimeoutMs must be positive" }
        require(stablePlaybackMs > 0) { "stablePlaybackMs must be positive" }
        require(baseDelayMs > 0) { "baseDelayMs must be positive" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must be at least baseDelayMs" }
        require(failureReportThreshold > 0) { "failureReportThreshold must be positive" }
        require(maxFiniteRecoveryAttempts >= 0) { "maxFiniteRecoveryAttempts must not be negative" }
        require(suppressionPollMs > 0) { "suppressionPollMs must be positive" }
    }
}
