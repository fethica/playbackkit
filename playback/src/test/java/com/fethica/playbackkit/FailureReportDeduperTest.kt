package com.fethica.playbackkit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureReportDeduperTest {

    private fun threshold(terminal: Boolean = false) =
        RecoveryEvent.FailureThresholdReached("live_a", 1, "r", retries = 6, failures = 6, terminal = terminal)

    private fun gaveUp(kind: ItemKind) =
        RecoveryEvent.GaveUp("item", 1, kind, "r", retries = 3, permanent = false)

    private fun reset(cause: LadderResetCause) = RecoveryEvent.LadderReset("item", 2, cause)

    @Test fun `a live incident is reported once`() {
        val deduper = FailureReportDeduper()
        assertTrue(deduper.shouldReport(threshold()))
        assertFalse(deduper.shouldReport(threshold()))
        assertFalse(deduper.shouldReport(threshold(terminal = true)))
    }

    @Test fun `a finite incident is reported on its first give-up only`() {
        val deduper = FailureReportDeduper()
        assertTrue(deduper.shouldReport(gaveUp(ItemKind.FINITE)))
        assertFalse(deduper.shouldReport(gaveUp(ItemKind.FINITE)))
    }

    @Test fun `a live give-up is covered by its terminal threshold event`() {
        assertFalse(FailureReportDeduper().shouldReport(gaveUp(ItemKind.LIVE)))
    }

    @Test fun `a stable stretch keeps the incident open`() {
        val deduper = FailureReportDeduper()
        deduper.shouldReport(threshold())
        deduper.shouldReport(gaveUp(ItemKind.FINITE))
        assertFalse(deduper.shouldReport(reset(LadderResetCause.STABLE_PLAYBACK)))
        assertFalse(deduper.shouldReport(reset(LadderResetCause.USER_SEEK)))
        assertFalse(deduper.shouldReport(threshold()))
        assertFalse(deduper.shouldReport(gaveUp(ItemKind.FINITE)))
    }

    @Test fun `a real transition starts a new incident`() {
        val deduper = FailureReportDeduper()
        deduper.shouldReport(threshold())
        deduper.shouldReport(gaveUp(ItemKind.FINITE))
        deduper.shouldReport(reset(LadderResetCause.ITEM_CHANGED))
        assertTrue(deduper.shouldReport(threshold()))
        assertTrue(deduper.shouldReport(gaveUp(ItemKind.FINITE)))
    }
}
