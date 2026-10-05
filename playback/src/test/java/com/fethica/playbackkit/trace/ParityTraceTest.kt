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
 * (player commands with their times, reports, UI signals, and the recovery state after
 * every step). The extra assertions pin what that shared behaviour is.
 *
 * Scenarios tagged KNOWN DEFECT pin current behaviour that is a candidate for a separate,
 * deliberate change. They are expected to change together with the reference when that
 * change is made, never silently.
 */
class ParityTraceTest {

    private val connectionFailed = 2001
    private val badHttpStatus = 2004

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
        val result = assertParity {
            select("live_a")
            ready()
            error(connectionFailed)
            select("live_a")
            advance(5_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
    }

    @Test fun `a same-id replace in the host bracket leaves the pending rung alone`() {
        val result = assertParity {
            select("live_a")
            ready()
            error(connectionFailed)
            metadataSwap()
            advance(1_000)
        }
        assertEquals(listOf("t=1000 stop", "t=1000 seekTo(0,0)", "t=1000 prepare", "t=1000 play"), result.recoveryCommands())
    }

    @Test fun `choosing a different item cancels the pending rung and resets the ladder`() {
        val result = assertParity {
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
        val result = assertParity {
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
        assertParity {
            select("live_a")
            assertEquals(PlayPauseAction.PAUSE, playPauseActionFor(fake.playWhenReady, STATE_BUFFERING))
            pause()
            advance(20_000)
        }
    }

    // Network.

    @Test fun `a network return after five rungs waits for the sixth-plus rung and is not a failure`() {
        val result = assertParity {
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
        val result = assertParity {
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
        val result = assertParity {
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
        val result = assertParity {
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
        val result = assertParity {
            select("live_a")
            advance(10_000)
            select("live_b")
            advance(5_000)
            advance(1_000)
        }
        assertEquals("t=16000 stop", result.recoveryCommands().first())
    }

    @Test fun `KNOWN DEFECT the stable window counts paused time`() {
        val result = assertParity {
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
        val result = assertParity {
            select("live_a")
            ready()
            advance(10_000)
            freeze(true)
            advance(20_000)
        }
        assertEquals("t=26000 stop", result.recoveryCommands().first())
    }

    @Test fun `buffering through a suppression restarts the buffering timeout`() {
        val result = assertParity {
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
        val result = assertParity {
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
        val result = assertParity {
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
            val result = assertParity {
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
        val result = assertParity {
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
        val result = assertParity {
            select("live_a")
            advance(15_000)
            advance(1_000)
            error(connectionFailed)
        }
        assertTrue(result.log.last().contains("retry=2 fail=2"))
    }

    @Test fun `a live item reaching its end reconnects without counting a failure`() {
        val result = assertParity {
            select("live_a")
            ready()
            ended()
            advance(1_000)
        }
        assertTrue(result.recoveryCommands().contains("t=1000 stop"))
        assertTrue(result.log.last().contains("retry=1 fail=0"))
    }

    @Test fun `a finite item's end and slow buffering are not failures`() {
        val result = assertParity {
            select("finite_x")
            advance(60_000)
            ready()
            ended()
            advance(10_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
    }

    @Test fun `a stable stretch resets the ladder but keeps the report until a real transition`() {
        val result = assertParity {
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
        val result = assertParity {
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
    }

    @Test fun `a host stop cancels recovery and vetoes it until the next play`() {
        val result = assertParity {
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
        val result = assertParity {
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
        val result = assertParity {
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
        val result = assertParity {
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
}
