package app.pumping.domain

import app.pumping.data.*
import java.math.BigDecimal
import java.math.MathContext
import java.time.*
import java.time.temporal.ChronoUnit

/** Inclusive local dates in the UI; exclusive next-midnight end, using session START membership. */
data class StatsWindow(val from: LocalDate, val until: LocalDate) {
    init { require(until > from) { "End date must be on or after start date" } }
    fun previous() = StatsWindow(from.minusDays(ChronoUnit.DAYS.between(from, until)), from)
    fun shift(periods: Long): StatsWindow {
        val days = Math.multiplyExact(ChronoUnit.DAYS.between(from, until), periods)
        return StatsWindow(from.plusDays(days), until.plusDays(days))
    }
}
enum class StatsRange(val label: String) { DAYS_7("Last 7 days"), DAYS_30("Last 30 days"), ALL("All time"), CUSTOM("Custom date range") }
fun statsWindow(range: StatsRange, today: LocalDate, customFrom: LocalDate, customEnd: LocalDate, offset: Int = 0): StatsWindow? {
    val window = when (range) {
        StatsRange.DAYS_7 -> StatsWindow(today.minusDays(6), today.plusDays(1))
        StatsRange.DAYS_30 -> StatsWindow(today.minusDays(29), today.plusDays(1))
        StatsRange.ALL -> return null
        StatsRange.CUSTOM -> StatsWindow(customFrom, customEnd.plusDays(1))
    }
    return window.shift(offset.toLong())
}
/** null pumpIds means all; setOf(null) means explicitly Unspecified. All predicates combine with AND. */
data class StatsFilter(
    val window: StatsWindow? = null,
    val pumpIds: Set<String?>? = null,
    val breastfed: BreastfeedingStatus? = null,
    val power: PowerPumpStatus? = null,
)
fun filterSessions(sessions: List<Session>, filter: StatsFilter, zone: ZoneId = ZoneId.systemDefault()): List<Session> {
    val lower = filter.window?.let { dayStart(it.from, zone) }
    val upper = filter.window?.let { dayStart(it.until, zone) }
    return sessions.distinctBy { it.id }.filter {
        (lower == null || it.start >= lower) && (upper == null || it.start < upper) &&
            (filter.pumpIds == null || it.pumpProfileId in filter.pumpIds) &&
            (filter.breastfed == null || it.breastfedBefore == filter.breastfed) &&
            (filter.power == null || it.powerStatus == filter.power)
    }
}
data class PumpStatistics(
    val count: Int, val volume: Long, val meanVolume: BigDecimal?, val medianVolume: BigDecimal?,
    val activeSeconds: Long, val meanActiveSeconds: BigDecimal?, val milkPerActiveMinute: BigDecimal?,
    val splitCount: Int, val left: Long?, val right: Long?,
    val elapsedCount: Int, val elapsedMillis: Long, val milkPerElapsedMinute: BigDecimal?,
)
private fun List<Long>.exactSum() = fold(0L, Math::addExact)
private fun divide(n: BigDecimal, d: Long): BigDecimal? = if (d <= 0) null else n.divide(BigDecimal(d), MathContext.DECIMAL128)
/** Parent rows only. Phases establish elapsed eligibility; they never supply volumes or session counts. */
fun pumpStatistics(sessions: List<Session>, phases: List<SessionPhase> = emptyList()): PumpStatistics {
    val parents = sessions.distinctBy { it.id }
    val volumes = parents.map { it.volume }.sorted()
    val total = volumes.exactSum()
    val active = parents.map { it.activeSeconds.coerceAtLeast(0) }.exactSum()
    val byParent = phases.groupBy { it.sessionId }
    val eligible = parents.mapNotNull { session ->
        runCatching { actualElapsedMillis(session, byParent[session.id].orEmpty()) }.getOrNull()?.let { session to it }
    }
    val elapsed = eligible.map { it.second }.exactSum()
    val split = parents.filter { it.left != null && it.right != null }
    val median = if (volumes.isEmpty()) null else if (volumes.size % 2 == 1) BigDecimal(volumes[volumes.size / 2])
        else BigDecimal(volumes[volumes.size / 2 - 1]).add(BigDecimal(volumes[volumes.size / 2])).divide(BigDecimal(2))
    return PumpStatistics(parents.size, total, divide(BigDecimal(total), parents.size.toLong()), median,
        active, divide(BigDecimal(active), parents.size.toLong()), divide(BigDecimal(total).multiply(BigDecimal(60)), active),
        split.size, if (split.isEmpty()) null else split.map { it.left!! }.exactSum(), if (split.isEmpty()) null else split.map { it.right!! }.exactSum(),
        eligible.size, elapsed, divide(BigDecimal(eligible.map { it.first.volume }.exactSum()).multiply(BigDecimal(60000)), elapsed))
}
enum class StatsGrouping(val label: String) { PUMP("Pump"), BREASTFEEDING("Breastfeeding context") }
data class StatsGroup(val key: String, val label: String, val statistics: PumpStatistics)
/** Filter once before every grouping so no comparison can accidentally use an unfiltered cohort. */
fun comparePumps(sessions: List<Session>, profiles: List<PumpProfile>, phases: List<SessionPhase>, filter: StatsFilter,
    grouping: StatsGrouping, zone: ZoneId = ZoneId.systemDefault()): List<StatsGroup> {
    val filtered = filterSessions(sessions, filter, zone)
    val phaseIndex = phases.groupBy { it.sessionId }
    fun summarize(rows: List<Session>) = pumpStatistics(rows, rows.flatMap { phaseIndex[it.id].orEmpty() })
    return when (grouping) {
        StatsGrouping.PUMP -> {
            val pumps = profiles.associateBy { it.id }
            filtered.groupBy { it.pumpProfileId }.map { (id, rows) ->
                val profile = pumps[id]
                StatsGroup(id ?: "", if (id == null) "Unspecified" else profile?.let { it.nickname + if (it.archived) " (archived)" else "" } ?: "Unavailable pump", summarize(rows))
            }.sortedWith(compareBy({ it.label.lowercase(java.util.Locale.ROOT) }, { it.key }))
        }
        StatsGrouping.BREASTFEEDING -> BreastfeedingStatus.entries.map { status ->
            StatsGroup(status.name, status.displayLabel(), summarize(filtered.filter { it.breastfedBefore == status }))
        }
    }
}
