package ai.edgefit.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class SessionTimelineTest {

    private val savedTz = TimeZone.getDefault()
    private val savedLocale = Locale.getDefault()

    @Before
    fun pinZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(savedTz)
        Locale.setDefault(savedLocale)
    }

    private fun record(
        history: List<Map<String, String>> = emptyList()
    ): SessionRecord = SessionRecord(
        sessionId = "SES-aaaaaaaaaaaaaaaa", mode = "byok",
        state = SessionState.SESSION_CREATED,
        createdAtUtc = "2026-10-28T17:45:03Z",
        updatedAtUtc = "2026-10-28T17:46:10Z",
        deviceModel = null, profileSha256 = null,
        modelSha256 = "0".repeat(64), modelSizeBytes = 1,
        modelDisplay = "m", workloadId = "w",
        promptSha256 = "1".repeat(64),
        config = emptyMap(), prediction = null,
        benchmark = null, comparison = null,
        followup = null, parentSessionId = null,
        experimentSeq = null, budgetUsed = 0,
        budgetMax = 5, agent = null, history = history,
        recoveryNotes = emptyList()
    )

    /** CLDR uses a narrow no-break space before AM/PM. */
    private fun norm(text: String): String =
        text.replace('\u202F', ' ').replace('\u00A0', ' ')

    @Test
    fun formatLocalRendersLongDateShortTime() {
        assertEquals("October 28, 2026  5:45 PM",
            norm(SessionTimeline.formatLocal("2026-10-28T17:45:03Z")))
    }

    @Test
    fun formatLocalRejectsMissingAndMalformed() {
        assertEquals(SessionTimeline.NOT_RECORDED,
            SessionTimeline.formatLocal(null))
        assertEquals(SessionTimeline.NOT_RECORDED,
            SessionTimeline.formatLocal(""))
        assertEquals(SessionTimeline.NOT_RECORDED,
            SessionTimeline.formatLocal("not-a-date"))
        assertEquals(SessionTimeline.NOT_RECORDED,
            SessionTimeline.formatLocal("2026-13-45T99:99:99Z"))
    }

    @Test
    fun stateLabelsCoverLifecycle() {
        assertEquals("Prediction recorded",
            SessionTimeline.stateLabel(
                SessionState.PREDICTION_RECORDED))
        assertEquals("Benchmark started",
            SessionTimeline.stateLabel(
                SessionState.BENCHMARK_RUNNING))
        assertEquals("Benchmark failed",
            SessionTimeline.stateLabel(
                SessionState.BENCHMARK_FAILED))
        assertEquals("Follow-up recorded",
            SessionTimeline.stateLabel(
                SessionState.RECOMMENDATION_RECORDED))
        // Unknown states pass through, never dropped.
        assertEquals("SOME_FUTURE_STATE",
            SessionTimeline.stateLabel("SOME_FUTURE_STATE"))
    }

    @Test
    fun eventsFollowHistoryOrderWithCauses() {
        val events = SessionTimeline.events(record(listOf(
            mapOf("state" to SessionState.SESSION_CREATED,
                "at" to "2026-10-28T17:45:03Z",
                "cause" to "created"),
            mapOf("state" to SessionState.PREDICTION_RECORDED,
                "at" to "2026-10-28T17:46:10Z",
                "cause" to "live_meta")
        )))
        assertEquals(2, events.size)
        assertEquals("Session created", events[0].label)
        assertEquals("October 28, 2026  5:45 PM", norm(events[0].local))
        assertEquals("Prediction recorded", events[1].label)
        assertEquals("live_meta", events[1].cause)
    }

    @Test
    fun eventsKeepMalformedTimestampsUnexpanded() {
        val events = SessionTimeline.events(record(listOf(
            mapOf("state" to SessionState.SESSION_CREATED,
                "at" to "garbage", "cause" to "created")
        )))
        assertEquals(1, events.size)
        assertEquals(SessionTimeline.NOT_RECORDED,
            events[0].local)
        assertEquals("garbage", events[0].utc)
    }

    @Test
    fun evidenceUtcKeepsAuthoritativeStamps() {
        val utc = SessionTimeline.evidenceUtc(record())
        assertTrue(utc.contains("2026-10-28T17:45:03Z"))
        assertTrue(utc.contains("2026-10-28T17:46:10Z"))
    }
}
