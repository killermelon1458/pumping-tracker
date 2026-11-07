package app.pumping.domain

import app.pumping.data.Session

enum class PumpReference(val label: String, val verb: String) {
    START("Start of last pump", "started"),
    END("End of last pump", "ended");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: START
    }
}

/** Timestamps remain untouched. Invalid old timing is never interpreted as an epoch-age pump. */
fun pumpReferenceTime(session: Session, reference: PumpReference): Long? {
    if (session.end <= 0) return null
    if (reference == PumpReference.END) return session.end
    if (session.start > 0 && session.start < session.end) return session.start
    if (session.activeSeconds <= 0 || session.activeSeconds > Long.MAX_VALUE / 1000) return null
    return (session.end - session.activeSeconds * 1000).takeIf { it > 0 && it < session.end }
}

fun latestPump(sessions: List<Session>) = sessions.filter { it.end > 0 }.maxByOrNull { it.end }

fun pumpElapsedText(milliseconds: Long): String {
    val minutes = milliseconds.coerceAtLeast(0) / 60000
    return if (minutes >= 60 && minutes % 60 == 0L) "${minutes / 60}h"
    else if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

fun pumpNotificationText(sessions: List<Session>, reference: PumpReference, now: Long): String {
    val last = latestPump(sessions.filter { it.end <= now }) ?: return "No previous pump recorded."
    val at = pumpReferenceTime(last, reference) ?: return "Last pump time is unavailable."
    return "Last pump ${reference.verb} ${pumpElapsedText(now - at)} ago"
}

/** Skip missed historical intervals, retaining the selected start/end cadence. */
fun nextAutomaticPump(at: Long, minutes: Int, now: Long): Long {
    val step = minutes.coerceIn(1, 10080) * 60000L
    val first = Math.addExact(at, step)
    return if (first > now) first else Math.addExact(first, ((now - first) / step + 1) * step)
}
