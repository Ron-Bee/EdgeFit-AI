package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for UX-01BC request lifecycle tracking. */
class RequestTrackerTest {

    @Test
    fun firstBeginWins() {
        val tracker = RequestTracker()
        assertTrue(tracker.begin("predict:1"))
        assertTrue(tracker.isActive("predict:1"))
    }

    @Test
    fun duplicateBeginRefused() {
        val tracker = RequestTracker()
        assertTrue(tracker.begin("followup:2"))
        assertFalse(tracker.begin("followup:2"))
    }

    @Test
    fun endReleasesKey() {
        val tracker = RequestTracker()
        tracker.begin("proposal:3")
        tracker.end("proposal:3")
        assertFalse(tracker.isActive("proposal:3"))
        assertTrue(tracker.begin("proposal:3"))
    }

    @Test
    fun distinctKeysAreIndependent() {
        val tracker = RequestTracker()
        assertTrue(tracker.begin("predict:1"))
        assertTrue(tracker.begin("predict:2"))
        tracker.end("predict:1")
        assertTrue(tracker.isActive("predict:2"))
    }
}
