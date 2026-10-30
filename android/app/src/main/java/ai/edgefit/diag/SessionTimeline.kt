package ai.edgefit.diag

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * EF-UX-01E session timestamps. All stored values are
 * authoritative UTC; this object only renders them in
 * local time. Missing or malformed values render as
 * "Not recorded" — never guessed, never backfilled.
 */
object SessionTimeline {

    const val NOT_RECORDED = "Not recorded"

    data class Event(
        val label: String,
        val utc: String,
        val local: String,
        val cause: String?
    )

    private fun parser(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }

    /**
     * "2026-10-28T17:45:03Z" -> "October 28, 2026  5:45 PM"
     * in the device locale and timezone.
     */
    fun formatLocal(utc: String?): String {
        if (utc.isNullOrBlank()) return NOT_RECORDED
        val at: Date = try {
            parser().parse(utc.trim()) ?: return NOT_RECORDED
        } catch (_: Exception) {
            return NOT_RECORDED
        }
        val date = DateFormat.getDateInstance(
            DateFormat.LONG).format(at)
        val time = DateFormat.getTimeInstance(
            DateFormat.SHORT).format(at)
        return "$date  $time"
    }

    /** Short local clock time for "Updated: 8:31 PM" lines. */
    fun formatClock(epochMs: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT)
            .format(Date(epochMs))

    fun stateLabel(state: String): String = when (state) {
        SessionState.SESSION_CREATED -> "Session created"
        SessionState.PREDICTION_PENDING -> "Prediction requested"
        SessionState.PREDICTION_RECORDED -> "Prediction recorded"
        SessionState.BENCHMARK_AUTHORIZED -> "Benchmark authorized"
        SessionState.BENCHMARK_RUNNING -> "Benchmark started"
        SessionState.BENCHMARK_COMPLETED -> "Benchmark completed"
        SessionState.BENCHMARK_FAILED -> "Benchmark failed"
        SessionState.BENCHMARK_CANCELLED -> "Benchmark cancelled"
        SessionState.BENCHMARK_TIMEOUT -> "Benchmark timed out"
        SessionState.COMPARISON_READY -> "Comparison ready"
        SessionState.FOLLOWUP_PENDING -> "Follow-up requested"
        SessionState.RECOMMENDATION_RECORDED -> "Follow-up recorded"
        SessionState.SESSION_FINISHED -> "Session finished"
        else -> state
    }

    /**
     * Chronological event list from the record's own
     * history (state/at/cause), which the manager appends
     * on every transition. Unknown states pass through
     * with their raw label; entries are never dropped.
     */
    fun events(record: SessionRecord): List<Event> {
        return record.history.map { entry ->
            val state = entry["state"] ?: "?"
            val at = entry["at"] ?: ""
            Event(
                label = stateLabel(state),
                utc = at,
                local = formatLocal(at),
                cause = entry["cause"]?.takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * Exact UTC evidence block for Technical Details:
     * creation, update, prediction, collection,
     * attachment, and follow-up stamps.
     */
    @Suppress("UNCHECKED_CAST")
    fun evidenceUtc(record: SessionRecord): String {
        val lines = mutableListOf(
            "created: ${record.createdAtUtc}",
            "updated: ${record.updatedAtUtc}"
        )
        (record.prediction as? Map<String, Any?>)?.let {
            lines.add("prediction: " +
                "${it["created_at_utc"] ?: NOT_RECORDED}")
        }
        (record.benchmark as? Map<String, Any?>)?.let {
            lines.add("collected: " +
                "${it["collected_at_utc"] ?: NOT_RECORDED}")
            lines.add("attached: " +
                "${it["attached_at_utc"] ?: NOT_RECORDED}")
        }
        (record.followup as? Map<String, Any?>)?.let {
            lines.add("follow-up: " +
                "${it["at"] ?: NOT_RECORDED}")
        }
        return lines.joinToString("\n")
    }
}
