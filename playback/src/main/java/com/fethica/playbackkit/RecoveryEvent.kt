package com.fethica.playbackkit

/**
 * What recovery did, delivered through [RecoveryHost.onEvent] on the player's looper.
 *
 * Every event names the item it is about and the [generation] that was current when it
 * was emitted. The generation increases on every new playback intent: a real item
 * transition, a play request, a pause that ends recovery, a seek the ladder did not make,
 * [PlaybackRecovery.cancelAutomaticWork] and detach. A host can compare generations to
 * drop an event that belongs to an intent the listener has already replaced.
 */
public sealed class RecoveryEvent {
    /** The media id of the item the event is about. */
    public abstract val mediaId: String?

    /** The playback-intent generation at the moment the event was emitted. */
    public abstract val generation: Long

    /**
     * A rung was scheduled.
     *
     * @param attempt the live backoff rung or the finite attempt, counted from 1.
     * @param positionMs the retained position a finite rung will re-prepare at; 0 for live.
     */
    public data class RungScheduled(
        override val mediaId: String?,
        override val generation: Long,
        val kind: ItemKind,
        val reason: String,
        val attempt: Int,
        val delayMs: Long,
        val positionMs: Long,
    ) : RecoveryEvent()

    /** A failure found no network; the next network arrival resumes playback. */
    public data class AwaitingNetwork(
        override val mediaId: String?,
        override val generation: Long,
        val kind: ItemKind,
        val reason: String,
    ) : RecoveryEvent()

    /** A due rung is holding until playback suppression (a call, for example) lifts. */
    public data class WaitingForSuppression(
        override val mediaId: String?,
        override val generation: Long,
    ) : RecoveryEvent()

    /** A finite item was re-prepared at [positionMs] and asked to play. */
    public data class FiniteRecovered(
        override val mediaId: String?,
        override val generation: Long,
        val attempt: Int,
        val positionMs: Long,
    ) : RecoveryEvent()

    /**
     * A live failure reached the report threshold, or is terminal.
     *
     * Emitted on every live failure at or past [RecoveryConfig.failureReportThreshold],
     * and on every terminal live failure. Wire a [FailureReportDeduper] to report once per
     * incident.
     *
     * @param retries the backoff rungs climbed so far.
     * @param failures the failures counted toward the threshold.
     */
    public data class FailureThresholdReached(
        override val mediaId: String?,
        override val generation: Long,
        val reason: String,
        val retries: Int,
        val failures: Int,
        val terminal: Boolean,
    ) : RecoveryEvent()

    /**
     * Recovery gave up and stopped the player. Emitted on every give-up, so a host can
     * answer each one (a message for the listener every time they press play on audio that
     * will not come) while reporting analytics once through a [FailureReportDeduper].
     *
     * @param retries live: the backoff rungs climbed; finite: the attempts made.
     * @param permanent the failure was classified permanent; false means the budget ran out.
     */
    public data class GaveUp(
        override val mediaId: String?,
        override val generation: Long,
        val kind: ItemKind,
        val reason: String,
        val retries: Int,
        val permanent: Boolean,
    ) : RecoveryEvent() {
        /** Always true: nothing further happens on its own after a give-up. */
        public val terminal: Boolean get() = true
    }

    /** Ladder counters were cleared. */
    public data class LadderReset(
        override val mediaId: String?,
        override val generation: Long,
        val cause: LadderResetCause,
    ) : RecoveryEvent()
}

/** Why the ladder counters were cleared. */
public enum class LadderResetCause {
    /** A real item transition: a different item, or the same item chosen again. */
    ITEM_CHANGED,

    /** Playback ran for the stable window. Report state is deliberately kept. */
    STABLE_PLAYBACK,

    /** A seek the ladder did not make re-armed the finite attempt budget. */
    USER_SEEK,
}
