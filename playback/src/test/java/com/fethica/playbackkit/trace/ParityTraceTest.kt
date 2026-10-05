package com.fethica.playbackkit.trace

import com.fethica.playbackkit.ItemKind
import com.fethica.playbackkit.PlayPauseAction
import com.fethica.playbackkit.RecoveryEvent
import com.fethica.playbackkit.RecoveryPolicy
import com.fethica.playbackkit.STATE_BUFFERING
import com.fethica.playbackkit.playPauseActionFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Differential traces: every scenario runs against [ReferenceRecovery] and the library's
 * engine on the same fake player and virtual clock, and the two logs must be identical
 * (player commands with their times, host actions, reports, UI signals, and the recovery
 * state after every step). The reference has no events, so each scenario also pins the
 * library's full event list. The extra assertions pin what that shared behaviour is.
 *
 * Scenarios tagged KNOWN DEFECT pin current behaviour that is a candidate for a separate,
 * deliberate change. They are expected to change together with the reference when that
 * change is made, never silently.
 */
class ParityTraceTest {

    private val connectionFailed = 2001
    private val badHttpStatus = 2004

    private val err = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"
    private val http404 = "ERROR_CODE_IO_BAD_HTTP_STATUS:404"

    private fun liveRung(t: Long, g: Int, id: String, reason: String, attempt: Int, delay: Long) =
        "t=$t g=$g rung LIVE $id $reason attempt=$attempt delay=$delay pos=0"

    private fun finiteRung(t: Long, g: Int, reason: String, attempt: Int, delay: Long, pos: Long) =
        "t=$t g=$g rung FINITE finite_x $reason attempt=$attempt delay=$delay pos=$pos"

    /** Fails and lets each rung run, [n] times: the ladder climbs to rung [n]. */
    private fun Trace.climb(n: Int) {
        repeat(n) { i ->
            error(connectionFailed)
            advance(RecoveryPolicy.retryDelayMs(i + 1))
        }
    }

    private fun TraceResult.commandsBetween(fromExclusive: Long, toExclusive: Long): List<String> =
        recoveryCommands().filter {
            val t = it.substringAfter("t=").substringBefore(" ").toLong()
            t > fromExclusive && t < toExclusive
        }

    // Same-id selection versus an in-place replace.

    @Test fun `choosing the item already playing resets recovery`() {
        val events = listOf(
            "t=0 g=1 reset item live_a",
            liveRung(0, 2, "live_a", err, 1, 1_000),
            "t=0 g=3 reset item live_a",
        )
        val result = assertParity(events) {
            select("live_a")
            ready()
            error(connectionFailed)
            select("live_a")
            advance(5_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
    }

    @Test fun `a same-id replace in the host bracket leaves the pending rung alone`() {
        val events = listOf("t=0 g=1 reset item live_a", liveRung(0, 2, "live_a", err, 1, 1_000))
        val result = assertParity(events) {
            select("live_a")
            ready()
            error(connectionFailed)
            sameIdReplace()
            advance(1_000)
        }
        assertEquals(listOf("t=1000 stop", "t=1000 seekTo(0,0)", "t=1000 prepare", "t=1000 play"), result.recoveryCommands())
    }

    @Test fun `a metadata update in place reports no transition and leaves the pending rung alone`() {
        val events = listOf("t=0 g=1 reset item live_a", liveRung(0, 2, "live_a", err, 1, 1_000))
        val result = assertParity(events) {
            select("live_a")
            ready()
            error(connectionFailed)
            metadataUpdate()
            advance(1_000)
        }
        assertTrue(result.log.any { "host replaceMediaItem(0,metadata)" in it })
        assertEquals(listOf("t=1000 stop", "t=1000 seekTo(0,0)", "t=1000 prepare", "t=1000 play"), result.recoveryCommands())
    }

    @Test fun `a finite seek after a metadata update still belongs to the item`() {
        val events = listOf(
            "t=0 g=1 reset item finite_x",
            finiteRung(5_000, 2, err, 1, 1_000, 5_000),
            "t=5000 g=3 reset seek finite_x",
        )
        val result = assertParity(events) {
            select("finite_x")
            ready()
            advance(5_000)
            metadataUpdate()
            error(connectionFailed)
            userSeek(1_000)
            advance(5_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
    }

    @Test fun `choosing a different item cancels the pending rung and resets the ladder`() {
        val events = listOf(
            "t=0 g=1 reset item live_a",
            liveRung(0, 2, "live_a", err, 1, 1_000),
            "t=0 g=3 reset item live_b",
        )
        val result = assertParity(events) {
            select("live_a")
            ready()
            error(connectionFailed)
            select("live_b")
            advance(5_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
    }

    // Transport.

    @Test fun `a permanent live failure stops with a terminal report and one tap prepares again`() {
        val events = listOf(
            "t=0 g=1 reset item live_a",
            "t=0 g=2 threshold live_a $http404 retries=1 failures=1 terminal=true",
            "t=0 g=2 gaveUp LIVE live_a $http404 retries=1 permanent=true",
        )
        val result = assertParity(events) {
            select("live_a")
            ready()
            error(badHttpStatus, 404)
            assertEquals(PlayPauseAction.PREPARE_AND_PLAY, playPauseActionFor(fake.playWhenReady, fake.playbackState))
            prepareAndPlay()
            ready()
            advance(10_000)
        }
        assertEquals(listOf("t=0 pause", "t=0 stop"), result.recoveryCommands())
        assertEquals(listOf("t=0 REPORT live reason=ERROR_CODE_IO_BAD_HTTP_STATUS:404 retries=1"), result.reports())
        val gaveUp = result.events.filterIsInstance<RecoveryEvent.GaveUp>().single()
        assertEquals(ItemKind.LIVE, gaveUp.kind)
        assertTrue(gaveUp.permanent)
    }

    @Test fun `buffering can be paused`() {
        assertParity(listOf("t=0 g=1 reset item live_a")) {
            select("live_a")
            assertEquals(PlayPauseAction.PAUSE, playPauseActionFor(fake.playWhenReady, STATE_BUFFERING))
            pause()
            advance(20_000)
        }
    }

    // Network.

    @Test fun `a network return after five rungs waits for the sixth-plus rung and is not a failure`() {
        val events = listOf(
            "t=0 g=1 reset item live_a",
            liveRung(0, 2, "live_a", err, 1, 1_000),
            liveRung(1_000, 2, "live_a", err, 2, 2_000),
            liveRung(3_000, 2, "live_a", err, 3, 4_000),
            liveRung(7_000, 2, "live_a", err, 4, 8_000),
            liveRung(15_000, 2, "live_a", err, 5, 16_000),
            "t=31000 g=2 threshold live_a $err retries=6 failures=6 terminal=false",
            "t=31000 g=2 awaiting LIVE live_a $err",
            "t=31000 g=2 threshold live_a network_restored retries=7 failures=6 terminal=false",
            liveRung(31_000, 2, "live_a", "network_restored", 7, 30_000),
        )
        val result = assertParity(events) {
            select("live_a")
            ready()
            climb(5)
            networkLost()
            error(connectionFailed)
            networkArrival()
            advance(29_999)
            advance(1)
        }
        assertEquals(emptyList<String>(), result.commandsBetween(31_000, 61_000))
        assertTrue(result.recoveryCommands().contains("t=61000 stop"))
        assertEquals(listOf("t=31000 REPORT live reason=ERROR_CODE_IO_NETWORK_CONNECTION_FAILED retries=6"), result.reports())
        assertTrue(result.log.last().contains("retry=7 fail=6"))
    }

    @Test fun `a network arrival after an item change creates no recovery`() {
        val events = listOf(
            "t=0 g=1 reset item live_a",
            "t=0 g=2 awaiting LIVE live_a $err",
            "t=0 g=3 reset item live_b",
        )
        val result = assertParity(events) {
            select("live_a")
            ready()
            networkLost()
            error(connectionFailed)
            select("live_b")
            networkArrival()
            advance(5_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
    }

    @Test fun `a finite network return spends a recovery attempt`() {
        val events = listOf(
            "t=0 g=1 reset item finite_x",
            "t=5000 g=2 awaiting FINITE finite_x $err",
            finiteRung(5_000, 2, "network_restored", 1, 1_000, 5_000),
            "t=6000 g=2 recovered finite_x attempt=1 pos=5000",
        )
        val result = assertParity(events) {
            select("finite_x")
            ready()
            advance(5_000)
            networkLost()
            error(connectionFailed)
            networkArrival()
            advance(1_000)
        }
        assertTrue(result.recoveryCommands().contains("t=6000 seekTo(0,5000)"))
        assertTrue(result.log.last().contains("attempts=1"))
    }

    // Watchdogs. KNOWN DEFECT scenarios pin current behaviour.

    @Test fun `KNOWN DEFECT resuming while still buffering arms no fresh buffering timeout`() {
        val result = assertParity(listOf("t=0 g=1 reset item live_a")) {
            select("live_a")
            advance(5_000)
            pause()
            advance(15_000)
            play()
            advance(60_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
    }

    @Test fun `KNOWN DEFECT an item change while buffering keeps the previous buffering deadline`() {
        val events = listOf(
            "t=0 g=1 reset item live_a",
            "t=10000 g=3 reset item live_b",
            liveRung(15_000, 3, "live_b", "buffer_timeout", 1, 1_000),
        )
        val result = assertParity(events) {
            select("live_a")
            advance(10_000)
            select("live_b")
            advance(5_000)
            advance(1_000)
        }
        assertEquals("t=16000 stop", result.recoveryCommands().first())
    }

    @Test fun `KNOWN DEFECT the stable window counts paused time`() {
        val events = listOf(
            "t=0 g=1 reset item live_a",
            liveRung(0, 2, "live_a", err, 1, 1_000),
            "t=31000 g=4 reset stable live_a",
        )
        val result = assertParity(events) {
            select("live_a")
            ready()
            error(connectionFailed)
            advance(1_000)
            ready()
            advance(5_000)
            pause()
            advance(24_000)
            play()
            advance(1_000)
        }
        assertTrue(result.log.last().contains("retry=0 fail=0"))
        assertTrue(
            result.events.any {
                it is RecoveryEvent.LadderReset && it.cause == com.fethica.playbackkit.LadderResetCause.STABLE_PLAYBACK
            },
        )
    }

    @Test fun `a frozen position while ready is a stall`() {
        val events = listOf("t=0 g=1 reset item live_a", liveRung(25_000, 2, "live_a", "stall_ready", 1, 1_000))
        val result = assertParity(events) {
            select("live_a")
            ready()
            advance(10_000)
            freeze(true)
            advance(20_000)
        }
        assertEquals("t=26000 stop", result.recoveryCommands().first())
    }

    @Test fun `buffering through a suppression restarts the buffering timeout`() {
        val events = listOf("t=0 g=1 reset item live_a", liveRung(30_000, 2, "live_a", "buffer_timeout", 1, 1_000))
        val result = assertParity(events) {
            select("live_a")
            suppress(true)
            advance(20_000)
            suppress(false)
            advance(10_000)
            advance(1_000)
        }
        assertEquals("t=31000 stop", result.recoveryCommands().first())
    }

    // Suppression and pauses.

    @Test fun `a long transient suppression never advances the ladder`() {
        val result = assertParity(listOf("t=0 g=1 reset item live_a")) {
            select("live_a")
            ready()
            advance(1_000)
            suppress(true)
            advance(60_000)
            suppress(false)
            advance(20_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
    }

    @Test fun `a due finite rung waits out a suppression longer than the whole budget`() {
        val events = listOf(
            "t=0 g=1 reset item finite_x",
            finiteRung(0, 2, err, 1, 1_000, 0),
            "t=1000 g=2 suppressed finite_x",
            "t=31000 g=2 recovered finite_x attempt=1 pos=0",
        )
        val result = assertParity(events) {
            select("finite_x")
            ready()
            error(connectionFailed)
            suppress(true)
            advance(30_000)
            suppress(false)
            advance(1_000)
        }
        assertEquals("t=31000 stop", result.recoveryCommands().first())
        assertEquals(1, result.events.count { it is RecoveryEvent.WaitingForSuppression })
    }

    @Test fun `a pause that ends recovery cancels the pending rung`() {
        listOf(
            RecoveryPolicy.PLAY_WHEN_READY_REASON_USER_REQUEST,
            RecoveryPolicy.PLAY_WHEN_READY_REASON_AUDIO_FOCUS_LOSS,
            RecoveryPolicy.PLAY_WHEN_READY_REASON_AUDIO_BECOMING_NOISY,
            RecoveryPolicy.PLAY_WHEN_READY_REASON_REMOTE,
        ).forEach { reason ->
            val result = assertParity(listOf("t=0 g=1 reset item live_a", liveRung(0, 2, "live_a", err, 1, 1_000))) {
                select("live_a")
                ready()
                error(connectionFailed)
                pause(reason)
                advance(5_000)
            }
            assertEquals("reason $reason", emptyList<String>(), result.recoveryCommands())
        }
    }

    @Test fun `a rung that outlives any other pause is dropped when it runs`() {
        val result = assertParity(listOf("t=0 g=1 reset item live_a", liveRung(0, 2, "live_a", err, 1, 1_000))) {
            select("live_a")
            ready()
            error(connectionFailed)
            pause(reason = 5)
            advance(5_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
    }

    // Guards and counting.

    @Test fun `the ladder's own stop and the idle after an error are not extra strikes`() {
        val events = listOf(
            "t=0 g=1 reset item live_a",
            liveRung(15_000, 2, "live_a", "buffer_timeout", 1, 1_000),
            liveRung(16_000, 2, "live_a", err, 2, 2_000),
        )
        val result = assertParity(events) {
            select("live_a")
            advance(15_000)
            advance(1_000)
            error(connectionFailed)
        }
        assertTrue(result.log.last().contains("retry=2 fail=2"))
    }

    @Test fun `a live item reaching its end reconnects without counting a failure`() {
        val events = listOf("t=0 g=1 reset item live_a", liveRung(0, 2, "live_a", "state_ended", 1, 1_000))
        val result = assertParity(events) {
            select("live_a")
            ready()
            ended()
            advance(1_000)
        }
        assertTrue(result.recoveryCommands().contains("t=1000 stop"))
        assertTrue(result.log.last().contains("retry=1 fail=0"))
    }

    @Test fun `a finite item's end and slow buffering are not failures`() {
        val result = assertParity(listOf("t=0 g=1 reset item finite_x")) {
            select("finite_x")
            advance(60_000)
            ready()
            ended()
            advance(10_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
    }

    @Test fun `a stable stretch resets the ladder but keeps the report until a real transition`() {
        val events = listOf(
            "t=0 g=1 reset item live_a",
            liveRung(0, 2, "live_a", err, 1, 1_000),
            liveRung(1_000, 2, "live_a", err, 2, 2_000),
            liveRung(3_000, 2, "live_a", err, 3, 4_000),
            liveRung(7_000, 2, "live_a", err, 4, 8_000),
            liveRung(15_000, 2, "live_a", err, 5, 16_000),
            "t=31000 g=2 threshold live_a $err retries=6 failures=6 terminal=false",
            liveRung(31_000, 2, "live_a", err, 6, 30_000),
            "t=91000 g=2 reset stable live_a",
            liveRung(91_000, 2, "live_a", err, 1, 1_000),
            "t=91000 g=3 reset item live_b",
        )
        val result = assertParity(events) {
            select("live_a")
            ready()
            climb(6)
            ready()
            advance(30_000)
            error(connectionFailed)
            select("live_b")
        }
        assertEquals(1, result.reports().size)
        assertTrue(result.log.any { it.contains("t=91000   retry=0 fail=0") && it.contains("liveRep=true") })
        assertTrue(result.log.last().contains("liveRep=false"))
    }

    // Retry sources and host stops.

    @Test fun `a replacement URI turns a permanent failure into a retry on the new URI`() {
        val moved = "https://example.test/live_a-moved"
        // Both shapes of the source replace: reporting a same-id transition, and not.
        listOf(true, false).forEach { reportsTransition ->
            val events = listOf("t=0 g=1 reset item live_a", liveRung(0, 2, "live_a", http404, 1, 1_000))
            val result = assertParity(events) {
                if (!reportsTransition) replaceReportsTransition(false)
                select("live_a")
                ready()
                sourceChange("live_a", moved)
                error(badHttpStatus, 404)
                advance(1_000)
            }
            assertEquals(
                listOf("t=1000 stop", "t=1000 replaceUri(0,$moved)", "t=1000 seekTo(0,0)", "t=1000 prepare", "t=1000 play"),
                result.recoveryCommands(),
            )
            assertEquals(emptyList<String>(), result.reports())
            assertTrue(result.log.last().contains("retry=1 fail=1"))
            assertTrue(result.finalState.contains("uri=$moved"))
        }
    }

    @Test fun `a host stop cancels recovery and vetoes it until the next play`() {
        val events = listOf(
            "t=0 g=1 reset item live_a",
            liveRung(0, 2, "live_a", err, 1, 1_000),
            liveRung(10_000, 5, "live_a", err, 2, 2_000),
        )
        val result = assertParity(events) {
            select("live_a")
            ready()
            error(connectionFailed)
            hostStop()
            advance(5_000)
            error(connectionFailed)
            advance(5_000)
            prepareAndPlay()
            error(connectionFailed)
            advance(2_000)
        }
        assertEquals(emptyList<String>(), result.commandsBetween(-1, 10_000))
        assertEquals("t=12000 stop", result.recoveryCommands().first())
    }

    // Finite recovery.

    @Test fun `a finite item retries at its position three times then gives up`() {
        val events = listOf(
            "t=0 g=1 reset item finite_x",
            finiteRung(10_000, 2, err, 1, 1_000, 10_000),
            "t=11000 g=2 recovered finite_x attempt=1 pos=10000",
            finiteRung(11_000, 2, err, 2, 2_000, 10_000),
            "t=13000 g=2 recovered finite_x attempt=2 pos=10000",
            finiteRung(13_000, 2, err, 3, 4_000, 10_000),
            "t=17000 g=2 recovered finite_x attempt=3 pos=10000",
            "t=17000 g=2 gaveUp FINITE finite_x $err retries=3 permanent=false",
        )
        val result = assertParity(events) {
            select("finite_x")
            ready()
            advance(10_000)
            error(connectionFailed)
            advance(1_000)
            ready()
            error(connectionFailed)
            advance(2_000)
            ready()
            error(connectionFailed)
            advance(4_000)
            ready()
            error(connectionFailed)
        }
        val seeks = result.recoveryCommands().filter { "seekTo" in it }
        assertEquals(listOf("t=11000 seekTo(0,10000)", "t=13000 seekTo(0,10000)", "t=17000 seekTo(0,10000)"), seeks)
        assertEquals(
            listOf("t=17000 REPORT finite reason=ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:exhausted"),
            result.reports(),
        )
        assertEquals(listOf("t=17000 UI finite failed id=finite_x"), result.uiSignals())
    }

    @Test fun `a listener's seek re-arms the finite budget and cancels the pending rung`() {
        val events = listOf(
            "t=0 g=1 reset item finite_x",
            finiteRung(0, 2, err, 1, 1_000, 0),
            "t=1000 g=2 recovered finite_x attempt=1 pos=0",
            finiteRung(1_000, 2, err, 2, 2_000, 0),
            "t=1000 g=3 reset seek finite_x",
            finiteRung(6_000, 3, err, 1, 1_000, 5_000),
            "t=7000 g=3 recovered finite_x attempt=1 pos=5000",
        )
        val result = assertParity(events) {
            select("finite_x")
            ready()
            error(connectionFailed)
            advance(1_000)
            ready()
            error(connectionFailed)
            userSeek(5_000)
            advance(5_000)
            prepareAndPlay()
            ready()
            error(connectionFailed)
            advance(1_000)
        }
        val attempts = result.events.filterIsInstance<RecoveryEvent.RungScheduled>().map { it.attempt }
        assertEquals(listOf(1, 2, 1), attempts)
        assertEquals("t=7000 seekTo(0,5000)", result.recoveryCommands().last { "seekTo" in it })
    }

    @Test fun `a repeated terminal finite failure signals every time and reports once`() {
        val events = listOf(
            "t=0 g=1 reset item finite_x",
            "t=0 g=2 gaveUp FINITE finite_x $http404 retries=0 permanent=true",
            "t=0 g=4 gaveUp FINITE finite_x $http404 retries=0 permanent=true",
        )
        val result = assertParity(events) {
            select("finite_x")
            ready()
            error(badHttpStatus, 404)
            prepareAndPlay()
            error(badHttpStatus, 404)
        }
        assertEquals(2, result.uiSignals().size)
        assertEquals(1, result.reports().size)
        assertEquals(2, result.events.count { it is RecoveryEvent.GaveUp })
    }

    // Host sleep timer settled when a rung runs.

    @Test fun `a timer found due when the rung runs stops playback and vetoes the rung`() {
        val events = listOf(
            "t=0 g=1 reset item live_a",
            liveRung(0, 2, "live_a", err, 1, 1_000),
            liveRung(6_000, 5, "live_a", err, 2, 2_000),
        )
        val result = assertParity(events) {
            select("live_a")
            ready()
            error(connectionFailed)
            timerDueAtNextSettle()
            advance(1_000)
            advance(5_000)
            prepareAndPlay()
            error(connectionFailed)
            advance(2_000)
        }
        assertTrue(result.log.contains("t=1000 host timer expired"))
        assertTrue(result.log.contains("t=1000 host pause"))
        assertTrue(result.log.contains("t=1000 host stop"))
        assertEquals(emptyList<String>(), result.commandsBetween(-1, 8_000))
        assertEquals("t=8000 stop", result.recoveryCommands().first())
    }

    @Test fun `a timer that expires during a long suppression is settled when the held rung runs`() {
        val events = listOf(
            "t=0 g=1 reset item live_a",
            liveRung(0, 2, "live_a", err, 1, 1_000),
            "t=1000 g=2 suppressed live_a",
        )
        val result = assertParity(events) {
            select("live_a")
            ready()
            error(connectionFailed)
            suppress(true)
            timerDueAtNextSettle()
            advance(30_000)
            suppress(false)
            advance(1_000)
            advance(10_000)
        }
        assertTrue(result.log.contains("t=31000 host timer expired"))
        assertEquals(emptyList<String>(), result.recoveryCommands())
        assertTrue(result.log.last().contains("pending=false"))
    }

    // Suppression and arrivals.

    @Test fun `a network arrival while suppressed schedules the next rung, which waits for the suppression`() {
        val events = listOf(
            "t=0 g=1 reset item live_a",
            "t=0 g=2 awaiting LIVE live_a $err",
            liveRung(0, 2, "live_a", "network_restored", 2, 2_000),
            "t=2000 g=2 suppressed live_a",
        )
        val result = assertParity(events) {
            select("live_a")
            ready()
            networkLost()
            error(connectionFailed)
            suppress(true)
            networkArrival()
            advance(5_000)
            suppress(false)
            advance(1_000)
        }
        assertEquals("t=6000 stop", result.recoveryCommands().first())
        assertTrue(result.log.last().contains("retry=2 fail=1"))
    }

    @Test fun `a queued not-playing callback read against a ready player is a failure`() {
        val events = listOf(
            "t=0 g=1 reset item live_a",
            liveRung(1_000, 3, "live_a", "ready_not_playing", 1, 1_000),
        )
        val result = assertParity(events) {
            select("live_a")
            ready()
            advance(1_000)
            pauseAndPlayInOneListenerTurn()
            advance(1_000)
        }
        assertEquals("t=2000 stop", result.recoveryCommands().first())
    }

    // The end of an item, with the host's own handling first.

    private fun Trace.handOffAtEnd(nextId: String) {
        onEnded = {
            log += "t=$now host completion id=${fake.currentMediaId}"
            if (fake.currentMediaId == "finite_x") {
                fake.setItem(nextId, uriOf(nextId))
                fake.prepare()
                fake.play()
            }
        }
    }

    @Test fun `a host that hands off to the next finite item at the end leaves recovery idle`() {
        val events = listOf("t=0 g=1 reset item finite_x", "t=5000 g=3 reset item finite_y")
        listOf(false, true).forEach { forward ->
            val result = assertParity(events, forward = forward) {
                handOffAtEnd("finite_y")
                select("finite_x")
                ready()
                advance(5_000)
                ended()
                advance(20_000)
            }
            assertTrue(result.log.contains("t=5000 host completion id=finite_x"))
            assertTrue(result.log.contains("t=5000 host setMediaItems(finite_y)"))
            assertEquals(emptyList<String>(), result.recoveryCommands())
            assertTrue(result.finalState.contains("item=finite_y"))
        }
    }

    @Test fun `a hand-off to a live item at the end schedules a rung the transition then cancels`() {
        val events = listOf(
            "t=0 g=1 reset item finite_x",
            liveRung(5_000, 2, "live_b", "state_ended", 1, 1_000),
            "t=5000 g=3 reset item live_b",
        )
        listOf(false, true).forEach { forward ->
            val result = assertParity(events, forward = forward) {
                handOffAtEnd("live_b")
                select("finite_x")
                ready()
                advance(5_000)
                ended()
                advance(5_000)
            }
            assertEquals(emptyList<String>(), result.recoveryCommands())
            assertTrue(result.log.last().contains("retry=0 fail=0"))
        }
    }

    @Test fun `forwarded callbacks drive the same recovery as a registered listener`() {
        val events = listOf(
            "t=0 g=1 reset item live_a",
            liveRung(0, 2, "live_a", err, 1, 1_000),
            liveRung(1_000, 2, "live_a", err, 2, 2_000),
            "t=3000 g=2 awaiting LIVE live_a $err",
            liveRung(3_000, 2, "live_a", "network_restored", 4, 8_000),
        )
        val result = assertParity(events, forward = true) {
            select("live_a")
            ready()
            climb(2)
            networkLost()
            error(connectionFailed)
            networkArrival()
            advance(8_000)
        }
        assertEquals("t=11000 stop", result.recoveryCommands().last { "stop" in it })
    }
}
