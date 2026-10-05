package com.fethica.playbackkit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The failure-report rule: a threshold for flapping items, at once for a terminal give-up. */
class FailureReportTest {
    @Test fun `below threshold does not report`() =
        assertFalse(RecoveryPolicy.shouldReportPlaybackFailure(retryCount = 3, threshold = 6, alreadyReported = false))

    @Test fun `at threshold reports once`() =
        assertTrue(RecoveryPolicy.shouldReportPlaybackFailure(retryCount = 6, threshold = 6, alreadyReported = false))

    @Test fun `does not report again once reported`() =
        assertFalse(RecoveryPolicy.shouldReportPlaybackFailure(retryCount = 9, threshold = 6, alreadyReported = true))

    @Test fun `a terminal failure is reported on the first strike`() =
        assertTrue(
            RecoveryPolicy.shouldReportPlaybackFailure(
                retryCount = 1, threshold = 6, alreadyReported = false, terminal = true
            )
        )

    @Test fun `a terminal failure is still reported only once`() =
        assertFalse(
            RecoveryPolicy.shouldReportPlaybackFailure(
                retryCount = 1, threshold = 6, alreadyReported = true, terminal = true
            )
        )

    @Test fun `a transient failure still waits for the threshold`() =
        assertFalse(
            RecoveryPolicy.shouldReportPlaybackFailure(
                retryCount = 3, threshold = 6, alreadyReported = false, terminal = false
            )
        )
}

