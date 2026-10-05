package com.fethica.playbackkit.trace

import com.fethica.playbackkit.FailureReportDeduper
import com.fethica.playbackkit.ItemKind
import com.fethica.playbackkit.LadderResetCause
import com.fethica.playbackkit.PLAYBACK_STATE_ENDED
import com.fethica.playbackkit.RecoveryCallbacks
import com.fethica.playbackkit.RecoveryConfig
import com.fethica.playbackkit.RecoveryEngine
import com.fethica.playbackkit.RecoveryEvent
import com.fethica.playbackkit.RecoveryHost
import com.fethica.playbackkit.RecoveryPolicy
import com.fethica.playbackkit.STATE_BUFFERING
import com.fethica.playbackkit.STATE_READY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/** Which implementation a trace drives. */
internal enum class Impl { REFERENCE, LIBRARY }

/** Ids starting with "finite" are finite items; everything else is live. */
internal fun kindOf(mediaId: String?): ItemKind =
    if (mediaId?.startsWith("finite") == true) ItemKind.FINITE else ItemKind.LIVE

internal fun uriOf(mediaId: String): String = "https://example.test/$mediaId"

/** What a trace produced. */
internal class TraceResult(
    val log: List<String>,
    val timedEvents: List<TimedEvent>,
    val finalState: String,
    val finalPlayWhenReady: Boolean,
    val finalPlaybackState: Int,
) {
    /** Commands recovery issued on its own, as "t=<ms> <command>". */
    fun recoveryCommands(): List<String> =
        log.filter { " rec " in it }.map { it.replace(" rec ", " ") }

    fun reports(): List<String> = log.filter { "REPORT" in it }

    fun uiSignals(): List<String> = log.filter { " UI " in it }

    /** The library's events; empty for the reference. */
    val events: List<RecoveryEvent> get() = timedEvents.map { it.event }

    /** Every event as one line: "t=<ms> g=<generation> <kind> <fields>". */
    fun eventLines(): List<String> = timedEvents.map { it.toString() }
}

/** An event with the virtual time it was delivered at. */
internal class TimedEvent(val atMs: Long, val event: RecoveryEvent) {
    override fun toString(): String {
        val e = event
        val fields = when (e) {
            is RecoveryEvent.RungScheduled ->
                "rung ${e.kind} ${e.mediaId} ${e.reason} attempt=${e.attempt} delay=${e.delayMs} pos=${e.positionMs}"
            is RecoveryEvent.AwaitingNetwork -> "awaiting ${e.kind} ${e.mediaId} ${e.reason}"
            is RecoveryEvent.WaitingForSuppression -> "suppressed ${e.mediaId}"
            is RecoveryEvent.FiniteRecovered -> "recovered ${e.mediaId} attempt=${e.attempt} pos=${e.positionMs}"
            is RecoveryEvent.FailureThresholdReached ->
                "threshold ${e.mediaId} ${e.reason} retries=${e.retries} failures=${e.failures} terminal=${e.terminal}"
            is RecoveryEvent.GaveUp ->
                "gaveUp ${e.kind} ${e.mediaId} ${e.reason} retries=${e.retries} permanent=${e.permanent}"
            is RecoveryEvent.LadderReset -> "reset ${e.cause.label()} ${e.mediaId}"
        }
        return "t=$atMs g=${e.generation} $fields"
    }

    private fun LadderResetCause.label(): String = when (this) {
        LadderResetCause.ITEM_CHANGED -> "item"
        LadderResetCause.STABLE_PLAYBACK -> "stable"
        LadderResetCause.USER_SEEK -> "seek"
    }
}

/**
 * One trace world: a fake player on virtual time, a scripted network and catalog, the
 * host's veto flag and sleep timer, the host's own listener, and the implementation under
 * test wired to them.
 *
 * @param forward the library is not registered on the player; the host's listener
 *   forwards every callback to it after its own handling (`registerListener = false`).
 * @param preAttach runs on the player before recovery is attached, with nobody listening:
 *   the player recovery takes over may already be prepared (library only).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class Trace(
    val impl: Impl,
    config: RecoveryConfig = RecoveryConfig(),
    forward: Boolean = false,
    preAttach: (FakePlayer.() -> Unit)? = null,
) {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val clock: () -> Long = { scheduler.currentTime }
    val log = mutableListOf<String>()
    val fake = FakePlayer(clock, log)
    private var online = true
    private val catalog = mutableMapOf<String, String>()
    private var vetoActive = false
    private var timerDue = false
    private val timedEvents = mutableListOf<TimedEvent>()
    private val deduper = FailureReportDeduper()

    /** The library's events so far. */
    val events: List<RecoveryEvent> get() = timedEvents.map { it.event }

    /** Library only: the host's answer to each event, run inside [RecoveryHost.onEvent]. */
    var onEvent: ((RecoveryEvent) -> Unit)? = null

    /** Library only: extra host work inside [RecoveryHost.onBeforeAutomaticStart]. */
    var onBeforeStart: (() -> Unit)? = null

    /** Library only: extra host work inside [RecoveryHost.retrySourceFor]. */
    var onRetrySource: (() -> Unit)? = null

    /**
     * The host's end-of-item handling, run by the host's listener on ENDED before recovery
     * hears it: completion bookkeeping, then whatever hand-off the host makes.
     */
    var onEnded: (() -> Unit)? = null

    private val reference: ReferenceRecovery?
    private val engine: RecoveryEngine?

    /** The library engine, for tests that call it directly. */
    val libraryEngine: RecoveryEngine get() = engine!!

    /** The virtual time now. */
    val now: Long get() = clock()

    /** Runs whatever is due now on the virtual clock, without logging a step. */
    fun runCurrent() = scheduler.runCurrent()

    init {
        preAttach?.let { setUp -> fake.asHost { fake.setUp() } }
        when (impl) {
            Impl.REFERENCE -> {
                reference = ReferenceRecovery(
                    port = fake,
                    serviceScope = scope,
                    clock = clock,
                    kindOf = ::kindOf,
                    network = { online },
                    catalogUriFor = { id -> id?.let { catalog[it] } },
                    log = log,
                    settleDue = ::settleSleepTimer,
                )
                engine = null
                fake.listeners += hostListener(forwardTo = null)
                fake.listeners += reference
            }
            Impl.LIBRARY -> {
                reference = null
                val host = object : RecoveryHost {
                    override fun itemKind(mediaId: String?): ItemKind = kindOf(mediaId)
                    override fun hasNetwork(): Boolean = online
                    override fun onBeforeAutomaticStart() {
                        settleSleepTimer(manualPlay = false)
                        onBeforeStart?.let { fake.asHost(it) }
                    }
                    override fun automaticStartAllowed(): Boolean = !vetoActive
                    override fun retrySourceFor(mediaId: String?, currentUri: String?, permanent: Boolean): String? {
                        onRetrySource?.let { fake.asHost(it) }
                        return mediaId?.let { catalog[it] }
                    }
                    override fun onEvent(event: RecoveryEvent) = onLibraryEvent(event)
                }
                engine = RecoveryEngine(fake, host, config, scope, clock)
                // The host's own listener, registered first.
                fake.listeners += hostListener(forwardTo = if (forward) engine else null)
                if (!forward) fake.listeners += engine
                engine.onAttached()
            }
        }
    }

    /**
     * The host's listener. For the library it holds what the host owns around recovery:
     * a play request lifts the veto and settles a due timer quietly, audio starting settles
     * one in full. The reference keeps those statements in its own arms. For both, ENDED
     * runs [onEnded] first. With [forwardTo], every callback is then forwarded.
     */
    private fun hostListener(forwardTo: RecoveryCallbacks?): RecoveryCallbacks = object : RecoveryCallbacks {
        val library = impl == Impl.LIBRARY

        override fun onMediaItemTransition(mediaId: String?, reason: Int) {
            forwardTo?.onMediaItemTransition(mediaId, reason)
        }

        override fun onPlayerError(errorCode: Int, errorCodeName: String, httpStatus: Int?) {
            forwardTo?.onPlayerError(errorCode, errorCodeName, httpStatus)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == PLAYBACK_STATE_ENDED) onEnded?.let { fake.asHost(it) }
            forwardTo?.onPlaybackStateChanged(playbackState)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (library && isPlaying) settleSleepTimer(manualPlay = false)
            forwardTo?.onIsPlayingChanged(isPlaying)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (library && playWhenReady) {
                vetoActive = false
                settleSleepTimer(manualPlay = true)
            }
            forwardTo?.onPlayWhenReadyChanged(playWhenReady, reason)
        }

        override fun onPositionDiscontinuity(reason: Int) {
            forwardTo?.onPositionDiscontinuity(reason)
        }
    }

    /**
     * The host's sleep timer settle. Nothing is due unless [timerDueAtNextSettle] armed it;
     * then the next settle consumes it. A press of play settles quietly; anything else with
     * play requested runs the host's stop: cancel automatic work, raise the veto, pause,
     * and stop a live item.
     */
    private fun settleSleepTimer(manualPlay: Boolean): Any? {
        if (!timerDue) return null
        timerDue = false
        if (manualPlay || !fake.playWhenReady) {
            log += "t=${clock()} host timer settled quietly"
            return QUIET
        }
        log += "t=${clock()} host timer expired"
        when (impl) {
            Impl.REFERENCE -> {
                reference!!.cancelAutomaticWork()
                reference.sleepStopActive = true
            }
            Impl.LIBRARY -> {
                engine!!.cancelAutomaticWork()
                vetoActive = true
            }
        }
        fake.asHost {
            fake.pause()
            if (kindOf(fake.currentMediaId) == ItemKind.LIVE) fake.stop()
        }
        return FULL
    }

    private fun onLibraryEvent(event: RecoveryEvent) {
        val now = clock()
        timedEvents += TimedEvent(now, event)
        if (deduper.shouldReport(event)) {
            when (event) {
                is RecoveryEvent.FailureThresholdReached ->
                    log += "t=$now REPORT live reason=${event.reason} retries=${event.retries}"
                is RecoveryEvent.GaveUp -> {
                    val reason = if (event.permanent) event.reason else "${event.reason}:exhausted"
                    log += "t=$now REPORT finite reason=$reason"
                }
                else -> Unit
            }
        }
        if (event is RecoveryEvent.GaveUp && event.kind == ItemKind.FINITE && !event.mediaId.isNullOrBlank()) {
            log += "t=$now UI finite failed id=${event.mediaId}"
        }
        onEvent?.let { answer -> fake.asHost { answer(event) } }
    }

    private fun snapshotLine(): String {
        val s = reference?.snapshot() ?: engine!!.snapshot()
        val liveReported = reference?.playbackFailureReported ?: deduper.liveReported
        val finiteReported = reference?.finiteFailureReported ?: deduper.finiteReported
        return "t=${clock()}   retry=${s.retryCount} fail=${s.failureCount} attempts=${s.finiteAttempts} " +
            "awaiting=${s.awaitingNetwork} pending=${s.retryPending} buf=${s.bufferingArmed} " +
            "stall=${s.stallArmed} reset=${s.ladderResetArmed} liveRep=$liveReported finRep=$finiteReported | " +
            fake.describe()
    }

    /**
     * Logs [label], runs [action], runs whatever is due now, and logs the state. Commands
     * issued directly by [action] are the host's, except while virtual time advances:
     * work that comes due then is recovery's own.
     */
    private fun step(label: String, hostDriving: Boolean = true, action: () -> Unit) {
        log += "t=${clock()} > $label"
        fake.hostDriving = hostDriving
        try {
            action()
        } finally {
            fake.hostDriving = false
        }
        scheduler.runCurrent()
        log += snapshotLine()
    }

    // region Script steps

    /** The host chooses an item: `setMediaItems`, `prepare`, `play`. */
    fun select(id: String, uri: String = uriOf(id)) = step("select $id") {
        fake.setItem(id, uri)
        fake.prepare()
        fake.play()
    }

    /** A source replace of the current item with its id kept, inside the host bracket. */
    fun sameIdReplace() = step("same-id replace in the host bracket") {
        val index = fake.currentMediaItemIndex
        val uri = fake.currentUri!!
        when (impl) {
            Impl.REFERENCE -> reference!!.hostReplace { fake.replaceUri(index, uri) }
            Impl.LIBRARY -> engine!!.runHostReplace { fake.replaceUri(index, uri) }
        }
    }

    /** A metadata refresh with the URI unchanged: Media3 updates in place, no transition. */
    fun metadataUpdate() = step("metadata update in place") { fake.updateMetadata() }

    /** Whether the fake reports a transition for a source replace of the current item. */
    fun replaceReportsTransition(on: Boolean) = step("replace reports transition=$on") {
        fake.replaceReportsTransition = on
    }

    /**
     * A pause and a play issued together from inside a listener callback: their callbacks
     * are delivered afterwards, so the isPlaying=false one is read against a player that
     * is READY with play requested again.
     */
    fun pauseAndPlayInOneListenerTurn() = step("pause and play in one listener turn") {
        fake.inOneListenerTurn {
            fake.pause()
            fake.play()
        }
    }

    /**
     * The host's sleep timer is past its deadline but its job has not woken: the next
     * settle (a failure's entry, a rung about to run, audio starting) finds it due.
     */
    fun timerDueAtNextSettle() = step("sleep timer due at the next settle") { timerDue = true }

    fun ready() = step("ready") { fake.moveTo(STATE_READY) }
    fun buffering() = step("buffering") { fake.moveTo(STATE_BUFFERING) }
    fun ended() = step("ended") { fake.moveTo(PLAYBACK_STATE_ENDED) }
    fun error(code: Int, httpStatus: Int? = null) = step("error $code/$httpStatus") { fake.fail(code, httpStatus) }
    fun suppress(on: Boolean) = step("suppression ${if (on) "on" else "off"}") { fake.suppress(on) }
    fun freeze(on: Boolean) = step("position ${if (on) "frozen" else "moving"}") { fake.freeze(on) }

    fun pause(reason: Int = RecoveryPolicy.PLAY_WHEN_READY_REASON_USER_REQUEST) = step("pause reason=$reason") {
        if (reason == RecoveryPolicy.PLAY_WHEN_READY_REASON_USER_REQUEST) fake.pause() else fake.pauseFor(reason)
    }

    fun play() = step("play") { fake.play() }

    /** The transport's PREPARE_AND_PLAY: one tap on a stopped player. */
    fun prepareAndPlay() = step("prepare and play") {
        fake.prepare()
        fake.play()
    }

    fun userSeek(positionMs: Long) = step("user seek $positionMs") { fake.userSeek(positionMs) }

    fun networkLost() = step("network lost") { online = false }

    /** The network is back but no arrival has been signalled yet. */
    fun networkBackWithoutArrival() = step("network back, no arrival yet") { online = true }

    fun networkArrival() = step("network arrival") {
        online = true
        when (impl) {
            Impl.REFERENCE -> reference!!.onNetworkArrival()
            Impl.LIBRARY -> engine!!.onNetworkArrival()
        }
    }

    /** A host stop on purpose (a sleep timer): cancel automatic work, veto, pause, stop if live. */
    fun hostStop() = step("host stop") {
        when (impl) {
            Impl.REFERENCE -> {
                reference!!.cancelAutomaticWork()
                reference.sleepStopActive = true
            }
            Impl.LIBRARY -> {
                engine!!.cancelAutomaticWork()
                vetoActive = true
            }
        }
        fake.pause()
        if (kindOf(fake.currentMediaId) == ItemKind.LIVE) fake.stop()
    }

    fun sourceChange(id: String, uri: String) = step("catalog moves $id to $uri") { catalog[id] = uri }

    fun advance(ms: Long) = step("advance $ms", hostDriving = false) { scheduler.advanceTimeBy(ms.milliseconds) }

    fun detach() = step("detach") { engine?.detach() }

    // endregion

    fun finish(): TraceResult {
        scope.cancel()
        return TraceResult(log.toList(), timedEvents.toList(), fake.describe(), fake.playWhenReady, fake.playbackState)
    }

    private companion object {
        val QUIET = Any()
        val FULL = Any()
    }
}

/** Runs [script] against one implementation. */
internal fun runTrace(
    impl: Impl,
    config: RecoveryConfig = RecoveryConfig(),
    forward: Boolean = false,
    preAttach: (FakePlayer.() -> Unit)? = null,
    script: Trace.() -> Unit,
): TraceResult {
    val trace = Trace(impl, config, forward, preAttach)
    trace.script()
    return trace.finish()
}

private fun isSignal(line: String): Boolean = " REPORT " in line || " UI " in line

/**
 * The log arranged for comparison, one block per step: everything but report and UI
 * signals in order, then the signals in order.
 *
 * The library hands a [RecoveryEvent] to the host only after the transaction that raised
 * it, so a report the reference writes just before the commands of the same decision (a
 * terminal live failure's report, then pause and stop) is written just after them by the
 * library. The comparison keeps every command, state line and signal in order and in its
 * step; only the interleaving of signals with commands inside one step is not compared.
 */
internal fun comparableLog(log: List<String>): String {
    val blocks = mutableListOf<MutableList<String>>(mutableListOf())
    log.forEach { line -> if (" > " in line) blocks += mutableListOf(line) else blocks.last() += line }
    return blocks.joinToString("\n") { block ->
        (block.filterNot(::isSignal) + block.filter(::isSignal).map { "  signal $it" }).joinToString("\n")
    }
}

/**
 * Runs [script] against the reference and the library and asserts that both produced the
 * same log: every player command with its virtual time, every host action, every report
 * and UI signal, and the recovery state after every step (see [comparableLog]). When
 * [events] is given, the library's full event list must equal it too: the reference has
 * no events, so they are pinned per scenario. Returns the library's result.
 */
internal fun assertParity(
    events: List<String>? = null,
    forward: Boolean = false,
    script: Trace.() -> Unit,
): TraceResult {
    val reference = runTrace(Impl.REFERENCE, script = script)
    val library = runTrace(Impl.LIBRARY, forward = forward, script = script)
    assertEquals(comparableLog(reference.log), comparableLog(library.log))
    if (events != null) assertEquals(events.joinToString("\n"), library.eventLines().joinToString("\n"))
    return library
}
