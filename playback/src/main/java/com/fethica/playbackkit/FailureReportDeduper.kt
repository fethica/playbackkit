package com.fethica.playbackkit

/**
 * An optional analytics policy: report a failure once per incident.
 *
 * Feed it every [RecoveryEvent] and report when [shouldReport] says so. A live incident
 * is reported on its first [RecoveryEvent.FailureThresholdReached]; a finite incident on
 * its first [RecoveryEvent.GaveUp]. The flags survive a stable stretch on purpose, so an
 * item that flaps, holds for the stable window and flaps again is one report, not one per
 * cycle. They clear on a real item transition, which is what makes the dedup per
 * incident.
 *
 * Kept apart from the events themselves so UI feedback (every give-up) and analytics
 * (once per incident) stay separate decisions. Not thread-safe: call it on the looper the
 * events arrive on.
 */
public class FailureReportDeduper {
    internal var liveReported: Boolean = false
        private set
    internal var finiteReported: Boolean = false
        private set

    /** Whether [event] should be reported. Updates the incident state as a side effect. */
    public fun shouldReport(event: RecoveryEvent): Boolean = when (event) {
        is RecoveryEvent.FailureThresholdReached -> {
            val report = !liveReported
            liveReported = true
            report
        }
        is RecoveryEvent.GaveUp -> if (event.kind == ItemKind.FINITE) {
            val report = !finiteReported
            finiteReported = true
            report
        } else {
            false
        }
        is RecoveryEvent.LadderReset -> {
            if (event.cause == LadderResetCause.ITEM_CHANGED) {
                liveReported = false
                finiteReported = false
            }
            false
        }
        else -> false
    }
}
