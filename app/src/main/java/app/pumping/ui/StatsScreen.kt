package app.pumping.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pumping.data.*
import app.pumping.domain.*
import java.time.*
import java.time.temporal.ChronoUnit

@Composable
fun StatsScreen(
    sessions: List<Session>,
    bags: List<BagBalance>,
    moves: List<Movement>,
    s: Settings,
    now: Long,
    goal: () -> Unit,
    profiles: List<PumpProfile> = emptyList(),
    phases: List<SessionPhase> = emptyList(),
) {
    var period by rememberSaveable { mutableStateOf("Week") }
    var mode by rememberSaveable { mutableStateOf("Pump") }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var rangeName by rememberSaveable { mutableStateOf(StatsRange.DAYS_30.name) }
    var pumpOffset by rememberSaveable { mutableIntStateOf(0) }
    var pumpKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var breastfedName by rememberSaveable { mutableStateOf<String?>(null) }
    var powerName by rememberSaveable { mutableStateOf<String?>(null) }
    var groupingName by rememberSaveable { mutableStateOf(StatsGrouping.PUMP.name) }
    val zone = ZoneId.systemDefault()
    val today = dayOf(now, zone)
    val allBounds = remember(sessions, zone) {
        (sessions.minOfOrNull { dayOf(it.start, zone) } ?: today) to (sessions.maxOfOrNull { dayOf(it.start, zone) } ?: today)
    }
    val firstMovement = remember(moves, zone) { moves.minOfOrNull { dayOf(it.time, zone) } }
    var customFrom by rememberSaveable { mutableStateOf(today.minusDays(29).toString()) }
    var customEnd by rememberSaveable { mutableStateOf(today.toString()) }
    val range = StatsRange.valueOf(rangeName)
    val validDates = range != StatsRange.CUSTOM || LocalDate.parse(customEnd) >= LocalDate.parse(customFrom)
    val window = if (validDates) statsWindow(range, today, LocalDate.parse(customFrom), LocalDate.parse(customEnd), pumpOffset) else null
    val filter = StatsFilter(window, pumpKeys.takeIf { it.isNotEmpty() }?.map { it.ifEmpty { null } }?.toSet(),
        breastfedName?.let(BreastfeedingStatus::valueOf), powerName?.let(PowerPumpStatus::valueOf))
    val grouping = StatsGrouping.valueOf(groupingName)
    val stashFrom = when (period) {
        "Week" -> today.minusDays(6).plusWeeks(offset.toLong())
        "Month" -> today.withDayOfMonth(1).plusMonths(offset.toLong())
        else -> firstMovement ?: today
    }
    val stashUntil = when (period) { "Week" -> stashFrom.plusDays(7); "Month" -> stashFrom.plusMonths(1); else -> today.plusDays(1) }
    val from = if (mode == "Pump") window?.from ?: allBounds.first else stashFrom
    val until = if (mode == "Pump") window?.until ?: maxOf(today, allBounds.second).plusDays(1) else stashUntil
    val current = remember(sessions, filter, mode, from, until, validDates, zone) {
        if (mode == "Pump") if (validDates) filterSessions(sessions, filter, zone) else emptyList()
        else sessions.filter { dayOf(it.start) >= from && dayOf(it.start) < until }
    }
    val old = remember(sessions, filter, validDates, zone) {
        if (window != null && validDates) filterSessions(sessions, filter.copy(window = window.previous()), zone) else emptyList()
    }
    val summary = remember(current, phases) { pumpStatistics(current, phases) }
    val groups = remember(sessions, profiles, phases, filter, grouping, validDates, zone) {
        if (validDates) comparePumps(sessions, profiles, phases, filter, grouping, zone) else emptyList()
    }
    val total = summary.volume
    val prior = old.sumOf { it.volume }
    val ledger = remember(moves, from, until, zone) { moves.filter { dayOf(it.time) >= from && dayOf(it.time) < until } }
    val chartDays = ChronoUnit.DAYS.between(from, until).coerceAtLeast(1)
    val monthlyChart = (mode == "Pump" && (range == StatsRange.ALL || chartDays > 62)) || (mode == "Stash" && period == "All")
    val chart = remember(mode, current, ledger, zone) {
        if (mode == "Pump") current.groupBy { dayOf(it.start) }.mapValues { it.value.sumOf { a -> a.volume } }
        else ledger.groupBy { dayOf(it.time) }.mapValues { it.value.sumOf { a -> a.delta } }
    }
    LazyColumn(
        modifier = Modifier.testTag("stats-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Heading("${streak(sessions,today)}-day streak")
            Text(
                "Consecutive days with a pump, ending today or yesterday.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            CardBlock {
                Text("Countdown", fontWeight = FontWeight.Bold)
                val start = runCatching { LocalDate.parse(s.goalStart) }.getOrNull()
                val end = runCatching { LocalDate.parse(s.goalEnd) }.getOrNull()
                if (start != null && end != null && end > start) {
                    val days = ChronoUnit.DAYS.between(today, end)
                    val progress =
                        (ChronoUnit.DAYS.between(start, today).toFloat() /
                                ChronoUnit.DAYS.between(start, end))
                            .coerceIn(0f, 1f)
                    Text(
                        if (days >= 0) "$days days to go!" else "Goal date reached",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("Pump until $end")
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${(progress*100).toInt()}%")
                } else Text("Choose a goal for your pumping journey.")
                TextButton(goal) { Text("Edit journey and goal") }
            }
        }
        item {
            Text("Analytics", style = MaterialTheme.typography.headlineSmall)
            Choices(listOf("Pump", "Stash"), mode) { mode = it }
            if (mode == "Stash") {
                Choices(listOf("Week", "Month", "All"), period) { period = it; offset = 0 }
                if (period != "All") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ offset-- }) { Text("‹ Previous") }
                    TextButton({ offset++ }, enabled = offset < 0) { Text("Next ›") }
                }
                Text("$from – ${until.minusDays(1)}")
            }
        }
        if (mode == "Pump") {
            item {
                PumpFilters(range, profiles, pumpKeys, filter.breastfed, filter.power, if (range == StatsRange.CUSTOM) window?.from ?: LocalDate.parse(customFrom) else LocalDate.parse(customFrom),
                    if (range == StatsRange.CUSTOM) window?.until?.minusDays(1) ?: LocalDate.parse(customEnd) else LocalDate.parse(customEnd),
                    { rangeName = it.name; pumpOffset = 0 }, { pumpKeys = it }, { breastfedName = it?.name }, { powerName = it?.name },
                    { startDate, date ->
                        customFrom = (if (startDate) date else window?.from ?: LocalDate.parse(customFrom)).toString()
                        customEnd = (if (startDate) window?.until?.minusDays(1) ?: LocalDate.parse(customEnd) else date).toString()
                        pumpOffset = 0
                    },
                    { rangeName = StatsRange.DAYS_30.name; pumpOffset = 0; pumpKeys = emptyList(); breastfedName = null; powerName = null;
                        customFrom = today.minusDays(29).toString(); customEnd = today.toString() })
            }
            item {
                if (range != StatsRange.ALL && validDates) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ pumpOffset-- }, Modifier.testTag("stats-previous")) { Text("‹ Previous") }
                    TextButton({ pumpOffset++ }, Modifier.testTag("stats-next"), enabled = pumpOffset < 0) { Text("Next ›") }
                }
                Text(if (validDates) "$from – ${until.minusDays(1)}" else "Choose a valid date range")
                Text("Dates use the session's local start date. All filters combine. Streak and goal above describe your whole journey.", style = MaterialTheme.typography.bodySmall)
            }
            item { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { PumpSummary(summary, s) } }
            if (window != null && validDates) item {
                CardBlock {
                    Text("Previous equal-length period: ${quantity(prior,s)} · ${old.size} sessions")
                    Text("Change: ${quantity(total-prior,s)} · ${comparison(total,prior)?.let{String.format(java.util.Locale.getDefault(),"%.1f%%",it)} ?: "N/A (no previous volume)"}")
                    Text("Same pump, breastfeeding and mode filters.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item { PumpComparison(groups, grouping, s) { groupingName = it.name } }
        } else item {
            CardBlock(Modifier.testTag("stats-stash-summary")) {
                val added = ledger.filter { it.delta > 0 }.sumOf { it.delta }
                val used = -ledger.filter { it.delta < 0 }.sumOf { it.delta }
                Text("Added ${quantity(added,s)}")
                Text("Used ${quantity(used,s)}")
                Text("Net ${quantity(added-used,s)}", color = MaterialTheme.colorScheme.secondary)
                Text("Current inventory: ${quantity(bags.sumOf{it.remaining},s)}")
                Text("Inventory totals are independent of Pump filters.", style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            Text(
                if (mode == "Pump") "Daily output" else "Daily inventory change",
                fontWeight = FontWeight.Bold,
            )
            if (monthlyChart) Text("Grouped by month for this range.", style = MaterialTheme.typography.bodySmall)
            val maximum = (chart.values.maxOfOrNull { kotlin.math.abs(it) } ?: 0).coerceAtLeast(1)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).height(220.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // All-time chart groups by month to avoid rendering years of empty daily columns.
                val points =
                    if (monthlyChart)
                        chart.entries
                            .groupBy { YearMonth.from(it.key) }.toSortedMap()
                            .map { (month, rows) -> month.toString() to rows.sumOf { it.value } }
                    else
                        (0 until chartDays).map { d ->
                            val date = from.plusDays(d)
                            date.toString() to (chart[date] ?: 0)
                        }
                val maxPoint =
                    (points.maxOfOrNull { kotlin.math.abs(it.second) } ?: maximum).coerceAtLeast(1)
                points.forEach { (label, value) ->
                    Column(
                        Modifier.width(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Text(
                            Volume.format(value, s.displayUnit, 1),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier.width(32.dp)
                                .height(
                                    (kotlin.math.abs(value).toFloat() / maxPoint * 140)
                                        .coerceAtLeast(3f)
                                        .dp
                                )
                                .background(
                                    if (mode == "Stash") MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(10.dp),
                                )
                        )
                        Text(if (monthlyChart) label else label.takeLast(5), style = MaterialTheme.typography.labelSmall)
                        if (mode == "Pump" && !monthlyChart)
                            Text(
                                "${current.count{dayOf(it.start).toString()==label}} pumps",
                                style = MaterialTheme.typography.labelSmall,
                            )
                    }
                }
            }
        }
        item {
            CardBlock {
                Text(
                    if (mode == "Pump") "Discoveries" else "Pumping discoveries (same dates)",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("${current.map{dayOf(it.start)}.distinct().size} active days in this period.")
                Text(
                    "Average per session: ${statsQuantity(summary.meanVolume,s)}"
                )
                val peak =
                    current
                        .groupBy {
                            Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).hour
                        }
                        .maxByOrNull { it.value.sumOf { a -> a.volume }.toDouble() / it.value.size }
                Text(
                    peak?.let {
                        "Peak hour by average output: ${it.key}:00 · ${it.value.size} samples"
                    } ?: "Peak hours appear after you record sessions."
                )
                val split = current.filter { it.left != null }
                Text("Left vs Right (${split.size} classified sessions)")
                Text(
                    "${quantity(split.sumOf{it.left ?: 0},s)} / ${quantity(split.sumOf{it.right ?: 0},s)}"
                )
                Text(
                    "Unclassified output: ${quantity(current.filter{it.left==null}.sumOf{it.volume},s)}"
                )
            }
        }
        item {
            CardBlock {
                Text("Estimated savings", style = MaterialTheme.typography.titleLarge)
                val fed = moves.filter { it.kind == "Feeding" }.sumOf { -it.delta }
                val rate = s.savingsPerLiter.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                Text(
                    "${java.math.BigDecimal(fed).divide(java.math.BigDecimal(1000000)).multiply(rate).setScale(2,java.math.RoundingMode.HALF_UP)} currency units"
                )
                Text(
                    "Based on all-time milk used for feeding and your cost per liter in Profile, independent of Pump filters. An estimate, not a purchase record."
                )
            }
        }
    }
}
