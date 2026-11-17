package app.pumping.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pumping.data.*
import app.pumping.domain.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

@Composable
internal fun StatsDropdown(label: String, selected: String, options: List<String>, tag: String, change: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth().testTag(tag)) { Text("$label: $selected ▾") }
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, text -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; change(i) }) }
        }
    }
}

@Composable
internal fun PumpFilters(range: StatsRange, pumps: List<PumpProfile>, pumpKeys: List<String>,
    breastfed: BreastfeedingStatus?, power: PowerPumpStatus?, customFrom: LocalDate, customEnd: LocalDate,
    changeRange: (StatsRange) -> Unit, changePumps: (List<String>) -> Unit,
    changeBreastfed: (BreastfeedingStatus?) -> Unit, changePower: (PowerPumpStatus?) -> Unit,
    changeDate: (Boolean, LocalDate) -> Unit, reset: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pumpMenu by remember { mutableStateOf(false) }
    var datePicker by rememberSaveable { mutableStateOf<String?>(null) }
    val labels = pumps.associate { it.id to (it.nickname + if (it.archived) " (archived)" else "") }
    val pumpLabel = if (pumpKeys.isEmpty()) "All pumps" else pumpKeys.joinToString { if (it.isEmpty()) "Unspecified" else labels[it] ?: "Unavailable pump" }
    CardBlock(Modifier.testTag("stats-filters")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ expanded = !expanded }, Modifier.testTag("stats-expand-filters")) { Text(if (expanded) "Hide filters ▴" else "Filters ▾") }
            TextButton(reset, Modifier.testTag("stats-reset")) { Text("Reset filters") }
        }
        Text("${range.label} · $pumpLabel · Breastfed: ${breastfed?.displayLabel() ?: "All"} · Mode: ${power?.statsLabel() ?: "All"}",
            Modifier.testTag("stats-active-filters"), style = MaterialTheme.typography.bodySmall)
        if (expanded) {
            StatsDropdown("Date range", range.label, StatsRange.entries.map { it.label }, "stats-date-range") { changeRange(StatsRange.entries[it]) }
            if (range == StatsRange.CUSTOM) {
                TextButton({ datePicker = "From" }, Modifier.testTag("stats-from")) { Text("From: $customFrom") }
                TextButton({ datePicker = "Through" }, Modifier.testTag("stats-through")) { Text("Through: $customEnd") }
                if (customEnd < customFrom) Text("End date must be on or after start date", color = MaterialTheme.colorScheme.error)
            }
            Box {
                OutlinedButton({ pumpMenu = true }, Modifier.fillMaxWidth().testTag("stats-pumps")) { Text("Pump: $pumpLabel ▾") }
                DropdownMenu(pumpMenu, { pumpMenu = false }) {
                    DropdownMenuItem(text = { Text("All pumps") }, onClick = { changePumps(emptyList()) }, modifier = Modifier.testTag("stats-all-pumps"))
                    (listOf("" to "Unspecified") + pumps.map { it.id to labels.getValue(it.id) }).forEach { (id, label) ->
                        val checked = id in pumpKeys
                        DropdownMenuItem(text = { Text(label) }, trailingIcon = { Checkbox(checked, null) },
                            modifier = Modifier.testTag("stats-pump-$id").semantics { selected = checked },
                            onClick = { changePumps(if (checked) pumpKeys - id else pumpKeys + id) })
                    }
                    DropdownMenuItem(text = { Text("Done") }, onClick = { pumpMenu = false }, modifier = Modifier.testTag("stats-pumps-done"))
                }
            }
            Text("Select one or more pumps. Archived pumps keep their historical sessions.", style = MaterialTheme.typography.bodySmall)
            StatsDropdown("Breastfed before pumping", breastfed?.displayLabel() ?: "All", listOf("All") + BreastfeedingStatus.entries.map { it.displayLabel() }, "stats-breastfed") {
                changeBreastfed(if (it == 0) null else BreastfeedingStatus.entries[it - 1])
            }
            StatsDropdown("Pumping mode", power?.statsLabel() ?: "All", listOf("All") + PowerPumpStatus.entries.map { it.statsLabel() }, "stats-power") {
                changePower(if (it == 0) null else PowerPumpStatus.entries[it - 1])
            }
        }
    }
    val context = LocalContext.current
    val latestDateChange by rememberUpdatedState(changeDate)
    if (datePicker != null) DisposableEffect(datePicker, context) {
        val from = datePicker == "From"
        val date = if (from) customFrom else customEnd
        val dialog = DatePickerDialog(context, { _, y, m, d -> latestDateChange(from, LocalDate.of(y, m + 1, d)) }, date.year, date.monthValue - 1, date.dayOfMonth)
        dialog.setOnDismissListener { datePicker = null }
        dialog.show()
        onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
    }
}
internal fun PowerPumpStatus.statsLabel() = when (this) {
    PowerPumpStatus.NORMAL -> "Normal"
    PowerPumpStatus.POWER -> "Power Pump"
    PowerPumpStatus.UNKNOWN -> "Not specified"
}
internal fun statsQuantity(value: BigDecimal?, settings: Settings) = value?.let { "${Volume.format(it, settings.displayUnit)} ${settings.displayUnit}" } ?: "N/A"
internal fun statsRate(value: BigDecimal?, settings: Settings) = if (value == null) "N/A" else "${statsQuantity(value, settings)}/min"
private fun statsMinutes(value: BigDecimal?) = value?.divide(BigDecimal(60), 2, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()?.plus(" min") ?: "N/A"

@Composable
internal fun PumpSummary(stats: PumpStatistics, settings: Settings) {
    CardBlock(Modifier.testTag("stats-summary")) {
        Text(quantity(stats.volume, settings), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text("${stats.count} sessions", Modifier.testTag("stats-count"))
        if (stats.count == 0) Text("No matching sessions. Adjust or reset filters to see your records.")
        Text("Mean per session: ${statsQuantity(stats.meanVolume, settings)}")
        Text("Median per session: ${statsQuantity(stats.medianVolume, settings)}")
        if (stats.count in 1..4) Text("Small sample: results may not be representative.", style = MaterialTheme.typography.bodySmall)
    }
    CardBlock(Modifier.testTag("stats-efficiency")) {
        Text("Efficiency", fontWeight = FontWeight.Bold)
        Text("Milk per pumping minute: ${statsRate(stats.milkPerActiveMinute, settings)}")
        Text("Active pumping time: ${statsMinutes(BigDecimal(stats.activeSeconds))}")
        Text("Mean active duration: ${statsMinutes(stats.meanActiveSeconds)}")
        Text("Known elapsed time: ${if (stats.elapsedCount == 0) "N/A" else statsMinutes(BigDecimal(stats.elapsedMillis).divide(BigDecimal(1000)))}")
        Text("Milk per known elapsed minute: ${statsRate(stats.milkPerElapsedMinute, settings)}")
        Text("Elapsed sample: ${stats.elapsedCount} of ${stats.count} sessions", Modifier.testTag("stats-elapsed-sample"))
        Text("Pumping rate = total milk ÷ active minutes. Elapsed rate includes milk and time only from sessions with known actual elapsed timing; nominal power-pump windows are excluded.", style = MaterialTheme.typography.bodySmall)
        Text("Left: ${stats.left?.let { quantity(it, settings) } ?: "N/A"} · Right: ${stats.right?.let { quantity(it, settings) } ?: "N/A"}")
        Text("${stats.splitCount} sessions with left/right quantities")
    }
}

@Composable
internal fun PumpComparison(groups: List<StatsGroup>, grouping: StatsGrouping, settings: Settings, change: (StatsGrouping) -> Unit) {
    CardBlock(Modifier.testTag("stats-comparisons")) {
        Text("Comparisons", style = MaterialTheme.typography.titleLarge)
        StatsDropdown("Group by", grouping.label, StatsGrouping.entries.map { it.label }, "stats-grouping") { change(StatsGrouping.entries[it]) }
        Text("All groups use the same active filters. Results describe expressed milk, not infant intake. Feeding context, time of day and sample size can differ; these are not pump rankings.", style = MaterialTheme.typography.bodySmall)
        if (groups.isEmpty()) Text("No matching groups.")
        groups.forEach { group ->
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().testTag("stats-group-${group.key}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(group.label, fontWeight = FontWeight.Bold)
                Text("${group.statistics.count} sessions")
                Text("Mean: ${statsQuantity(group.statistics.meanVolume, settings)} · Median: ${statsQuantity(group.statistics.medianVolume, settings)}")
                Text("Milk per pumping minute: ${statsRate(group.statistics.milkPerActiveMinute, settings)}")
                if (group.statistics.count in 1..4) Text("Small sample; interpret with care.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
