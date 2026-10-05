package com.fethica.playbackkit.sample

import com.fethica.playbackkit.sample.fixtures.LoopbackServer
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The developer-panel switches the host reads. Each is a plain read for the recovery host
 * and a flow for the UI.
 */
class DevSwitches {
    /** Makes [SampleRecoveryHost.hasNetwork] answer false whatever the system says. */
    val simulateOffline = MutableStateFlow(false)

    /** Makes [SampleRecoveryHost.automaticStartAllowed] answer false, like an expired sleep timer. */
    val sleepVeto = MutableStateFlow(false)

    /** Makes [SampleRecoveryHost.retrySourceFor] answer the live item's mirror URL. */
    val replaceSource = MutableStateFlow(false)
}

/** What the screen shows about recovery: a summary of the last event and the generation. */
data class RecoveryStatus(val summary: String = "idle", val generation: Long = 0)

/** What the developer panel asks of the running service. Called on the main thread. */
interface ServiceHooks {
    /** A host stop: veto on, cancel automatic work, pause. */
    fun sleepNow()

    /** Holds transient audio focus for [seconds], so the player is suppressed, not paused. */
    fun suppressFor(seconds: Int)
}

/**
 * The sample's shared state, in one process: the loopback server, the switches, the log
 * and the service hooks. A real app would inject these; a sample keeps them in one place.
 */
object SampleGraph {
    lateinit var server: LoopbackServer
        internal set

    val switches = DevSwitches()
    val log = EventLog()
    val recoveryStatus = MutableStateFlow(RecoveryStatus())

    /** Set by [PlaybackService] while it runs. */
    @Volatile
    var service: ServiceHooks? = null
}
