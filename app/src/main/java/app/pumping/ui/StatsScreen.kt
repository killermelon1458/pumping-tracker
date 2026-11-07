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
) {
    var period by rememberSaveable { mutableStateOf("Week") }
    var mode by rememberSaveable { mutableStateOf("Pump") }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val today = dayOf(now)
    val from =
        when (period) {
            "Week" -> today.minusDays(6).plusWeeks(offset.toLong())
            "Month" -> today.withDayOfMonth(1).plusMonths(offset.toLong())
            else ->
                if (mode == "Pump") sessions.minOfOrNull { dayOf(it.start) } ?: today
                else moves.minOfOrNull { dayOf(it.time) } ?: today
        }
    val until =
        when (period) {
            "Week" -> from.plusDays(7)
            "Month" -> from.plusMonths(1)
            else -> today.plusDays(1)
        }
    val previous = if (period == "Month") from.minusMonths(1) else from.minusDays(7)
    val current = sessions.filter { dayOf(it.start) >= from && dayOf(it.start) < until }
    val old = sessions.filter { dayOf(it.start) >= previous && dayOf(it.start) < from }
    val total = current.sumOf { it.volume }
    val prior = old.sumOf { it.volume }
    val ledger = moves.filter { dayOf(it.time) >= from && dayOf(it.time) < until }
    val chartDays = ChronoUnit.DAYS.between(from, until).coerceAtLeast(1)
    val chart =
        if (mode == "Pump")
            current.groupBy { dayOf(it.start) }.mapValues { it.value.sumOf { a -> a.volume } }
        else ledger.groupBy { dayOf(it.time) }.mapValues { it.value.sumOf { a -> a.delta } }
    LazyColumn(
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
            Choices(listOf("Week", "Month", "All"), period) {
                period = it
                offset = 0
            }
            if (period != "All")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ offset-- }) { Text("‹ Previous") }
                    TextButton({ offset++ }, enabled = offset < 0) { Text("Next ›") }
                }
            Text("$from – ${until.minusDays(1)}")
        }
        item {
            CardBlock {
                if (mode == "Pump") {
                    Text(
                        quantity(total, s),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${current.size} sessions · ${current.sumOf{it.activeSeconds}/60} active minutes"
                    )
                    if (period != "All") {
                        Text("Previous period: ${quantity(prior,s)} · ${old.size} sessions")
                        Text(
                            "Change: ${quantity(total-prior,s)} · ${comparison(total,prior)?.let{String.format(java.util.Locale.getDefault(),"%.1f%%",it)} ?: "N/A (no previous volume)"}"
                        )
                    }
                } else {
                    val added = ledger.filter { it.delta > 0 }.sumOf { it.delta }
                    val used = -ledger.filter { it.delta < 0 }.sumOf { it.delta }
                    Text("Added ${quantity(added,s)}")
                    Text("Used ${quantity(used,s)}")
                    Text(
                        "Net ${quantity(added-used,s)}",
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text("Current inventory: ${quantity(bags.sumOf{it.remaining},s)}")
                }
            }
        }
        item {
            Text(
                if (mode == "Pump") "Daily output" else "Daily inventory change",
                fontWeight = FontWeight.Bold,
            )
            val maximum = (chart.values.maxOfOrNull { kotlin.math.abs(it) } ?: 0).coerceAtLeast(1)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).height(220.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // All-time chart groups by month to avoid rendering years of empty daily columns.
                val points =
                    if (period == "All")
                        chart.entries
                            .groupBy { YearMonth.from(it.key) }
                            .map { (month, rows) -> month.toString() to rows.sumOf { it.value } }
                    else
                        (0 until chartDays).map { d ->
                            val date = from.plusDays(d)
                            date.dayOfMonth.toString() to (chart[date] ?: 0)
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
                        Text(label, style = MaterialTheme.typography.labelSmall)
                        if (mode == "Pump" && period != "All")
                            Text(
                                "${current.count{dayOf(it.start).dayOfMonth.toString()==label}} pumps",
                                style = MaterialTheme.typography.labelSmall,
                            )
                    }
                }
            }
        }
        item {
            CardBlock {
                Text(
                    "Discoveries",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("${current.map{dayOf(it.start)}.distinct().size} active days in this period.")
                Text(
                    "Average per session: ${quantity(if(current.isEmpty()) 0 else total/current.size,s)}"
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
                    "Based on milk used for feeding and your cost per liter in Profile. An estimate, not a purchase record."
                )
            }
        }
    }
}
