package com.fethica.playbackkit.trace

import com.fethica.playbackkit.ItemKind
import com.fethica.playbackkit.LadderResetCause
import com.fethica.playbackkit.RecoveryConfig
import com.fethica.playbackkit.RecoveryEvent
import com.fethica.playbackkit.STATE_READY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Library behaviour the reference has no counterpart for: lifecycle, events, generation, config. */
class LibraryTraceTest {

    @Test fun `detach cancels the pending rung and drops later arrivals and callbacks`() {
        val result = runTrace(Impl.LIBRARY) {
            select("live_a")
            ready()
            error(2001)
            detach()
            advance(5_000)
            networkArrival()
            error(2001)
            advance(5_000)
            detach()
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
    }

    @Test fun `a network arrival queued before detach does nothing after it`() {
        val result = runTrace(Impl.LIBRARY) {
            select("live_a")
            ready()
            networkLost()
            error(2001)
            libraryEngine.onNetworkArrival()
            libraryEngine.detach()
            runCurrent()
            advance(60_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
    }

    @Test fun `a network arrival from another thread runs on the looper, not on the caller`() {
        val result = runTrace(Impl.LIBRARY) {
            select("live_a")
            ready()
            networkLost()
            error(2001)
            networkBackWithoutArrival()
            val caller = Thread { libraryEngine.onNetworkArrival() }
            caller.start()
            caller.join()
            assertFalse(log.any { " rec stop" in it })
            assertTrue(libraryEngine.snapshot().awaitingNetwork)
            runCurrent()
            assertFalse(libraryEngine.snapshot().awaitingNetwork)
            advance(2_000)
        }
        assertTrue(result.recoveryCommands().contains("t=2000 stop"))
    }

    @Test fun `every give-up emits a terminal event`() {
        val result = runTrace(Impl.LIBRARY) {
            select("live_a")
            ready()
            error(2004, 404)
            prepareAndPlay()
            error(2004, 404)
        }
        val gaveUp = result.events.filterIsInstance<RecoveryEvent.GaveUp>()
        assertEquals(2, gaveUp.size)
        assertTrue(gaveUp.all { it.terminal && it.kind == ItemKind.LIVE && it.permanent })
        assertEquals(2, result.events.count { it is RecoveryEvent.FailureThresholdReached && it.terminal })
        assertEquals(1, result.reports().size)
    }

    @Test fun `events name their item and carry the generation of the intent they belong to`() {
        val result = runTrace(Impl.LIBRARY) {
            select("live_a")
            ready()
            error(2001)
            val scheduled = events.filterIsInstance<RecoveryEvent.RungScheduled>().single()
            assertEquals("live_a", scheduled.mediaId)
            assertEquals(libraryEngine.generation, scheduled.generation)
            val before = libraryEngine.generation
            pause()
            assertTrue(libraryEngine.generation > before)
            val afterPause = libraryEngine.generation
            play()
            assertTrue(libraryEngine.generation > afterPause)
            val afterPlay = libraryEngine.generation
            select("live_b")
            assertTrue(libraryEngine.generation > afterPlay)
        }
        val reset = result.events.filterIsInstance<RecoveryEvent.LadderReset>().last()
        assertEquals("live_b", reset.mediaId)
        assertEquals(LadderResetCause.ITEM_CHANGED, reset.cause)
    }

    @Test fun `a finite rung reports the position it recovered at`() {
        val result = runTrace(Impl.LIBRARY) {
            select("finite_x")
            ready()
            advance(4_000)
            error(2001)
            advance(1_000)
        }
        val recovered = result.events.filterIsInstance<RecoveryEvent.FiniteRecovered>().single()
        assertEquals(4_000L, recovered.positionMs)
        assertEquals(1, recovered.attempt)
    }

    @Test fun `the config drives the backoff and the watchdogs`() {
        val config = RecoveryConfig(bufferingTimeoutMs = 5_000L, baseDelayMs = 500L, maxDelayMs = 2_000L)
        val result = runTrace(Impl.LIBRARY, config) {
            select("live_a")
            advance(5_000)
            advance(500)
        }
        assertEquals("t=5500 stop", result.recoveryCommands().first())
        val delays = result.events.filterIsInstance<RecoveryEvent.RungScheduled>().map { it.delayMs }
        assertEquals(listOf(500L), delays)
    }

    @Test fun `cancelling automatic work forgets a pending network resume`() {
        val result = runTrace(Impl.LIBRARY) {
            select("live_a")
            ready()
            networkLost()
            error(2001)
            libraryEngine.cancelAutomaticWork()
            networkArrival()
            advance(60_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
    }

    // Hosts that answer events with player commands.

    /** Every command logged after the line containing [marker]. */
    private fun TraceResult.commandsAfter(marker: String): List<String> =
        log.dropWhile { marker !in it }.drop(1).filter { " rec " in it || " host " in it }

    @Test fun `a host that plays a fallback from a terminal event keeps it playing`() {
        val terminal: List<(RecoveryEvent) -> Boolean> = listOf(
            { it is RecoveryEvent.FailureThresholdReached && it.terminal },
            { it is RecoveryEvent.GaveUp },
        )
        terminal.forEach { isTerminal ->
            val result = runTrace(Impl.LIBRARY) {
                onEvent = { event ->
                    if (isTerminal(event) && fake.currentMediaId == "live_a") {
                        fake.setItem("live_b", uriOf("live_b"))
                        fake.prepare()
                        fake.play()
                    }
                }
                select("live_a")
                ready()
                error(2004, 404)
                advance(10_000)
            }
            // The give-up's own pause and stop come first; nothing recovery does follows the fallback.
            assertEquals(listOf("t=0 pause", "t=0 stop"), result.recoveryCommands())
            assertEquals(
                listOf("t=0 host setMediaItems(live_b)", "t=0 host prepare", "t=0 host play"),
                result.commandsAfter("rec stop"),
            )
            assertTrue(result.finalPlayWhenReady)
            assertTrue(result.finalState.contains("item=live_b"))
        }
    }

    @Test fun `a same-id reselection in answer to a watchdog's rung cancels that rung`() {
        val result = runTrace(Impl.LIBRARY) {
            onEvent = { event ->
                if (event is RecoveryEvent.RungScheduled) {
                    onEvent = null
                    fake.setItem("live_a", uriOf("live_a"))
                    fake.prepare()
                    fake.play()
                }
            }
            select("live_a")
            advance(15_000)
            assertFalse(libraryEngine.snapshot().retryPending)
            advance(10_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
        val lines = result.eventLines()
        assertEquals("t=15000 g=2 rung LIVE live_a buffer_timeout attempt=1 delay=1000 pos=0", lines[1])
        assertEquals("t=15000 g=3 reset item live_a", lines[2])
    }

    @Test fun `a seek in answer to a delayed rung keeps the new position`() {
        val result = runTrace(Impl.LIBRARY) {
            select("finite_x")
            ready()
            advance(10_000)
            networkLost()
            error(2001)
            onEvent = { event ->
                if (event is RecoveryEvent.RungScheduled) {
                    onEvent = null
                    fake.userSeek(2_000)
                }
            }
            networkArrival()
            advance(10_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
        assertFalse(result.log.any { "seekTo(0,10000)" in it })
        assertTrue(result.events.any { it is RecoveryEvent.LadderReset && it.cause == LadderResetCause.USER_SEEK })
    }

    @Test fun `a same-id reselection inside the pre-start call stops the rung from running`() {
        val result = runTrace(Impl.LIBRARY) {
            select("live_a")
            ready()
            error(2001)
            onBeforeStart = {
                onBeforeStart = null
                fake.setItem("live_a", uriOf("live_a"))
                fake.prepare()
            }
            advance(1_000)
            advance(5_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
        assertTrue(result.log.contains("t=1000 host setMediaItems(live_a)"))
    }

    @Test fun `a seek inside the pre-start call stops a finite rung from restoring the old position`() {
        val result = runTrace(Impl.LIBRARY) {
            select("finite_x")
            ready()
            advance(10_000)
            error(2001)
            onBeforeStart = {
                onBeforeStart = null
                fake.userSeek(2_000)
            }
            advance(1_000)
            advance(5_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
        assertFalse(result.log.any { "seekTo(0,10000)" in it })
    }

    @Test fun `a reselection inside the retry source call drops the failure it was asked for`() {
        val result = runTrace(Impl.LIBRARY) {
            select("live_a")
            onRetrySource = {
                onRetrySource = null
                fake.setItem("live_a", uriOf("live_a"))
                fake.prepare()
                fake.play()
            }
            advance(15_000)
            advance(10_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
        assertEquals(listOf("t=0 g=1 reset item live_a", "t=15000 g=3 reset item live_a"), result.eventLines())
    }

    // Attaching to a player that already holds an item.

    @Test fun `attaching to a ready player arms the stall watchdog`() {
        val result = runTrace(Impl.LIBRARY, preAttach = {
            setItem("live_a", uriOf("live_a"))
            prepare()
            play()
            moveTo(STATE_READY)
        }) {
            assertTrue(libraryEngine.snapshot().stallArmed)
            advance(10_000)
            freeze(true)
            advance(20_000)
        }
        assertEquals("t=26000 stop", result.recoveryCommands().first())
        // Attachment is not a selection: no reset was announced for it.
        assertEquals("t=25000 g=0 rung LIVE live_a stall_ready attempt=1 delay=1000 pos=0", result.eventLines().first())
    }

    @Test fun `attaching to a buffering player arms the buffering timeout`() {
        val result = runTrace(Impl.LIBRARY, preAttach = {
            setItem("live_a", uriOf("live_a"))
            prepare()
            play()
        }) {
            assertTrue(libraryEngine.snapshot().bufferingArmed)
            advance(15_000)
            advance(1_000)
        }
        assertEquals("t=16000 stop", result.recoveryCommands().first())
    }

    @Test fun `a finite seek before any transition after attaching is recognised`() {
        val result = runTrace(Impl.LIBRARY, preAttach = {
            setItem("finite_x", uriOf("finite_x"))
            prepare()
            play()
            moveTo(STATE_READY)
        }) {
            advance(5_000)
            error(2001)
            userSeek(1_000)
            advance(5_000)
        }
        assertEquals(emptyList<String>(), result.recoveryCommands())
        assertEquals(
            listOf(
                "t=5000 g=0 rung FINITE finite_x ERROR_CODE_IO_NETWORK_CONNECTION_FAILED attempt=1 delay=1000 pos=5000",
                "t=5000 g=1 reset seek finite_x",
            ),
            result.eventLines(),
        )
        assertTrue(result.log.last().contains("attempts=0"))
    }
}
