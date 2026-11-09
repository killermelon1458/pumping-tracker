package app.pumping.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pumping.data.*
import app.pumping.domain.*
import java.time.format.DateTimeFormatter

@Composable
fun PumpScreen(
    sessions: List<Session>,
    s: Settings,
    now: Long,
    timer: TimerState,
    reminder: () -> Unit,
    start: () -> Unit,
    log: () -> Unit,
    edit: (Session) -> Unit,
    stashedSessionIds: Set<String> = emptySet(),
) {
    val scroll = rememberLazyListState()
    LazyColumn(
        state = scroll,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Last Pump", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                latestPump(sessions)?.let { last ->
                    pumpReferenceTime(last, s.pumpReference)?.let {
                        "${gap((now-it).coerceAtLeast(0))} ago · ${s.pumpReference.verb}"
                    } ?: "Last pump time unavailable"
                } ?: "Your journey starts here",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                        Triple("Set Reminder", Icons.Outlined.Alarm, reminder),
                        Triple(
                            if (timer.id.isEmpty()) "Timer Start" else "Resume Timer",
                            Icons.Outlined.Timer,
                            start,
                        ),
                        Triple("Quick Log", Icons.Outlined.Add, log),
                    )
                    .forEach { (label, icon, action) ->
                        Surface(
                            Modifier.weight(1f).clickable(onClick = action),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(
                                Modifier.padding(vertical = 20.dp, horizontal = 5.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(icon, label, tint = MaterialTheme.colorScheme.primary)
                                Text(label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
            }
        }
        if (sessions.isEmpty())
            item {
                CardBlock {
                    Text("Record your first pump", fontWeight = FontWeight.Bold)
                    Text(
                        "Start a timer or quickly log a completed session. Your records stay on this device."
                    )
                }
            }
        val chronological = sessions.sortedBy { it.start }
        sessions
            .groupBy { dayOf(it.start) }
            .forEach { (date, rows) ->
                item(key = date.toString()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(date.format(DateTimeFormatter.ofPattern("EEE d MMM")))
                        Text("${rows.size} / ${rows.sumOf {it.activeSeconds}/60} min")
                        Text(quantity(rows.sumOf { it.volume }, s), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    CardBlock {
                        rows.forEachIndexed { i, row ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .testTag("session-${row.id}")
                                    .clickable { edit(row) }
                                    .padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        clock(row.start, s),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (row.id in stashedSessionIds)
                                            Icon(
                                                Icons.Outlined.AcUnit,
                                                "Stashed",
                                                Modifier.size(18.dp).testTag("stashed-${row.id}"),
                                                tint = Blue,
                                            )
                                        Text(quantity(row.volume, s), fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(
                                    "${row.activeSeconds/60} min ${row.activeSeconds%60}s",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                val index = chronological.indexOfFirst { it.id == row.id }
                                if (s.intervals && index > 0) {
                                    val delta =
                                        interval(chronological[index - 1], row, s.pumpReference)
                                    Text(
                                        if (delta < 0) "Overlaps previous session"
                                        else "${gap(delta)} since last pump",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(
                                    "Breastfed before pumping: ${row.breastfedBefore.name.lowercase().replaceFirstChar { it.uppercase() }}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (row.notes.isNotEmpty()) Text(row.notes)
                            }
                            if (i < rows.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }
    }
}
