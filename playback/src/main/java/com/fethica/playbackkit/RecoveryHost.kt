package com.fethica.playbackkit

/**
 * Whether an item plays forever or has an end.
 *
 * This is the one distinction recovery cannot make on its own, and it changes what
 * recovery means. A LIVE item is recovered by re-preparing at the live edge, so every
 * rung ends in `seekTo(index, 0)`. A FINITE item gets a bounded ladder that re-prepares
 * at the retained position instead.
 */
public enum class ItemKind {
    /** A continuous stream with no end. */
    LIVE,

    /** An item with a duration and a position worth keeping. */
    FINITE,
}

/**
 * Everything [PlaybackRecovery] needs from the app around it.
 *
 * Recovery knows nothing about catalogs, analytics, timers or UI. It asks this interface
 * the questions it cannot answer and reports back through [onEvent].
 *
 * Every method is called on the player's application looper, synchronously from inside
 * recovery's own work, and none may detach the recovery from inside a call.
 *
 * Only [onBeforeAutomaticStart] is meant to issue player commands. [itemKind],
 * [hasNetwork], [automaticStartAllowed] and [retrySourceFor] are queries. [onEvent] is
 * observational: it runs after the work that raised the event is complete, so it never
 * interrupts a decision, but a host that wants to answer an event with player commands
 * (play a fallback item, seek, reselect) should post them to a later turn of the looper.
 * A command issued from inside a player callback has its own callbacks queued behind the
 * one still being delivered, so recovery and every other listener would first hear the
 * rest of the old callbacks, describing the player as it was, while the player already
 * holds the new state. Posting keeps each side's callbacks in their own turn.
 */
public interface RecoveryHost {

    /** LIVE for a continuous stream, FINITE for an item with an end; decided per media id. */
    public fun itemKind(mediaId: String?): ItemKind

    /**
     * Whether the device has a network worth retrying on.
     *
     * This is the predicate for an actor, so it should be the fail-open one: the default
     * network offers the INTERNET capability, validated or not, and "online" when the
     * system cannot be asked. Believing "online" wrongly costs one request that fails and
     * a backoff. Believing "offline" wrongly, on a network that works but never validates
     * (a VPN, private DNS, a Wi-Fi network whose probe is blocked), parks playback until
     * the next network arrival. Validation is a UI hint, not a reason to stop retrying.
     *
     * Answering false makes a failure wait for [PlaybackRecovery.onNetworkArrival] instead
     * of scheduling a rung.
     */
    public fun hasNetwork(): Boolean

    /**
     * Called right before [automaticStartAllowed] wherever recovery is about to start audio
     * on its own: at the entry of every failure, and when a delayed rung is about to run.
     *
     * A host with a sleep timer settles a timer that is already past its deadline here. It
     * may pause or stop the player and call [PlaybackRecovery.cancelAutomaticWork] from
     * inside this call. Recovery reads the player again after it returns: a rung whose
     * item was chosen again, sought, paused or vetoed in the meantime does not run.
     */
    public fun onBeforeAutomaticStart() {}

    /**
     * The veto on automatic starts. False while the host has stopped playback on purpose
     * (a sleep timer expiry, for example) and only a person pressing play should start
     * audio again. Read at the entry of every failure, when a delayed rung is about to run,
     * and before the stall watchdog is re-armed after playback stops.
     *
     * Must be a plain read with no side effects.
     */
    public fun automaticStartAllowed(): Boolean = true

    /**
     * A different URI to retry this live item on, or null to retry the one it has.
     *
     * Asked when a live item fails (with [permanent] set as classified) and again right
     * before each rung re-prepares (with [permanent] false), since the answer can change
     * during the backoff. A non-null answer that differs from [currentUri] turns a
     * permanent failure into a retry, and the rung swaps the URI in place with the media id
     * kept, inside the same-id bracket, so the ladder does not read the swap as a new
     * selection. Never asked for finite items.
     *
     * A query. Should it issue player commands anyway, recovery reads the current item and
     * play intent again after the call and drops the failure or rung when they changed.
     */
    public fun retrySourceFor(mediaId: String?, currentUri: String?, permanent: Boolean): String? = null

    /**
     * A recovery event, delivered on the player's looper once the transaction that raised
     * it has finished: the commands it decided on are issued and the rung it announces is
     * installed and cancellable. Events from one transaction arrive in the order they were
     * raised. Observational; see the interface documentation for issuing commands.
     */
    public fun onEvent(event: RecoveryEvent) {}
}
