package com.fethica.playbackkit.trace

import com.fethica.playbackkit.ItemKind
import com.fethica.playbackkit.LadderResetCause
import com.fethica.playbackkit.RecoveryConfig
import com.fethica.playbackkit.RecoveryEvent
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
}
