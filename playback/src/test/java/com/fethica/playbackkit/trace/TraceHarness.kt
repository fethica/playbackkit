package com.fethica.playbackkit.trace

import com.fethica.playbackkit.FailureReportDeduper
import com.fethica.playbackkit.ItemKind
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
    val events: List<RecoveryEvent>,
    val finalState: String,
    val finalPlayWhenReady: Boolean,
    val finalPlaybackState: Int,
) {
    /** Commands recovery issued on its own, as "t=<ms> <command>". */
    fun recoveryCommands(): List<String> =
        log.filter { " rec " in it }.map { it.replace(" rec ", " ") }

    fun reports(): List<String> = log.filter { "REPORT" in it }

    fun uiSignals(): List<String> = log.filter { " UI " in it }
}

/**
 * One trace world: a fake player on virtual time, a scripted network and catalog, the
 * host's veto flag, and the implementation under test wired to them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class Trace(val impl: Impl, config: RecoveryConfig = RecoveryConfig()) {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val clock: () -> Long = { scheduler.currentTime }
    val log = mutableListOf<String>()
    val fake = FakePlayer(clock, log)
    private var online = true
    private val catalog = mutableMapOf<String, String>()
    private var vetoActive = false
    val events = mutableListOf<RecoveryEvent>()
    private val deduper = FailureReportDeduper()

    private val reference: ReferenceRecovery?
    private val engine: RecoveryEngine?

    /** The library engine, for tests that call it directly. */
    val libraryEngine: RecoveryEngine get() = engine!!

    /** Runs whatever is due now on the virtual clock, without logging a step. */
    fun runCurrent() = scheduler.runCurrent()

    init {
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
                )
                engine = null
                fake.listeners += reference
            }
            Impl.LIBRARY -> {
                reference = null
                val host = object : RecoveryHost {
                    override fun itemKind(mediaId: String?): ItemKind = kindOf(mediaId)
                    override fun hasNetwork(): Boolean = online
                    override fun automaticStartAllowed(): Boolean = !vetoActive
                    override fun retrySourceFor(mediaId: String?, currentUri: String?, permanent: Boolean): String? =
                        mediaId?.let { catalog[it] }
                    override fun onEvent(event: RecoveryEvent) = onLibraryEvent(event)
                }
                engine = RecoveryEngine(fake, host, config, scope, clock)
                // The host's own listener, registered first: any play request lifts its veto.
                fake.listeners += object : RecoveryCallbacks {
                    override fun onMediaItemTransition(mediaId: String?, reason: Int) {}
                    override fun onPlayerError(errorCode: Int, errorCodeName: String, httpStatus: Int?) {}
                    override fun onPlaybackStateChanged(playbackState: Int) {}
                    override fun onIsPlayingChanged(isPlaying: Boolean) {}
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (playWhenReady) vetoActive = false
                    }
                    override fun onPositionDiscontinuity(reason: Int) {}
                }
                fake.listeners += engine
            }
        }
    }

    private fun onLibraryEvent(event: RecoveryEvent) {
        events += event
        val now = clock()
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

    /** An in-place replace of the current item with its id kept, inside the host bracket. */
    fun metadataSwap() = step("same-id replace in the host bracket") {
        val index = fake.currentMediaItemIndex
        val uri = fake.currentUri!!
        when (impl) {
            Impl.REFERENCE -> reference!!.hostReplace { fake.replaceUri(index, uri) }
            Impl.LIBRARY -> engine!!.runHostReplace { fake.replaceUri(index, uri) }
        }
    }

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
        return TraceResult(log.toList(), events.toList(), fake.describe(), fake.playWhenReady, fake.playbackState)
    }
}

/** Runs [script] against one implementation. */
internal fun runTrace(
    impl: Impl,
    config: RecoveryConfig = RecoveryConfig(),
    script: Trace.() -> Unit,
): TraceResult {
    val trace = Trace(impl, config)
    trace.script()
    return trace.finish()
}

/**
 * Runs [script] against the reference and the library and asserts that both produced the
 * same log: every player command with its virtual time, every report and UI signal, and
 * the recovery state after every step. Returns the library's result.
 */
internal fun assertParity(script: Trace.() -> Unit): TraceResult {
    val reference = runTrace(Impl.REFERENCE, script = script)
    val library = runTrace(Impl.LIBRARY, script = script)
    assertEquals(reference.log.joinToString("\n"), library.log.joinToString("\n"))
    return library
}
