package app.pumping.domain

import app.pumping.data.*

data class PhaseTotals(val active: Long, val elapsed: Long)

fun phaseTotals(phases: List<SessionPhase>): PhaseTotals {
    require(phases.isNotEmpty() && phases.size <= 200) { "Enter 1–200 intervals or remove timing details" }
    var active = 0L
    var elapsed = 0L
    phases.forEachIndexed { index, p ->
        require(p.position == index && p.seconds > 0) { "Intervals must be ordered and have positive durations" }
        require(p.id.isNotBlank() && p.id.length <= 512) { "Invalid interval ID" }
        elapsed = Math.addExact(elapsed, p.seconds)
        if (p.kind == PhaseKind.PUMP) active = Math.addExact(active, p.seconds)
    }
    require(phases.map { it.id }.distinct().size == phases.size) { "Duplicate interval IDs" }
    require(active > 0) { "Include at least one pumping interval" }
    Math.multiplyExact(elapsed, 1000)
    return PhaseTotals(active, elapsed)
}

fun validateSessionPhases(session: Session, phases: List<SessionPhase>) {
    if (phases.isEmpty()) return
    require(session.powerStatus == PowerPumpStatus.POWER && session.elapsedKnown) { "Intervals require a power pump with recorded elapsed timing" }
    require(phases.all { it.sessionId == session.id }) { "Missing or incorrect interval parent" }
    val total = phaseTotals(phases)
    val span = Math.subtractExact(session.end, session.start)
    val spanMatches = if (session.mode == "TIMER") span / 1000 == total.elapsed else span == Math.multiplyExact(total.elapsed, 1000)
    require(total.active == session.activeSeconds && spanMatches) {
        "Intervals must match the session's active time and elapsed span"
    }
}

fun powerPreset(sessionId: String): List<SessionPhase> = listOf(
    PhaseKind.PUMP to 1200L, PhaseKind.REST to 600L, PhaseKind.PUMP to 600L,
    PhaseKind.REST to 600L, PhaseKind.PUMP to 600L,
).mapIndexed { index, (kind, seconds) -> SessionPhase(sessionId = sessionId, position = index, kind = kind, seconds = seconds) }

/** Interval totals are authoritative; only a manual timestamp edit selects the fixed endpoint. */
data class RoutineTiming(val start: Long, val end: Long, val timestamp: TimeField = TimeField.END) {
    init { require(timestamp != TimeField.DURATION) }
    fun resize(elapsedMillis: Long): RoutineTiming {
        require(elapsedMillis > 0)
        val next = if (timestamp == TimeField.START) copy(end = Math.addExact(start, elapsedMillis))
            else copy(start = Math.subtractExact(end, elapsedMillis))
        require(next.start > 0 && next.end > next.start) { "Invalid session interval" }
        return next
    }
    fun edit(field: TimeField, value: Long, elapsedMillis: Long): RoutineTiming {
        require(field != TimeField.DURATION)
        return (if (field == TimeField.START) copy(start = value, timestamp = field)
            else copy(end = value, timestamp = field)).resize(elapsedMillis)
    }
}

fun phaseBoundaries(session: Session, phases: List<SessionPhase>): List<Pair<Long, Long>> {
    validateSessionPhases(session, phases)
    var at = session.start
    return phases.mapIndexed { index, p ->
        val end = if (index == phases.lastIndex) session.end else Math.addExact(at, Math.multiplyExact(p.seconds, 1000))
        (at to end).also { at = end }
    }
}

/** Stage 4 eligibility only; never include nominal power-pump windows as measured elapsed time. */
fun actualElapsedMillis(session: Session, phases: List<SessionPhase> = emptyList()): Long? {
    if (!session.elapsedKnown || session.end <= session.start) return null
    if (session.powerStatus == PowerPumpStatus.POWER && session.mode != "TIMER" && phases.isEmpty()) return null
    validateSession(session)
    validateSessionPhases(session, phases)
    return session.end - session.start
}
