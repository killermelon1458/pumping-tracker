package app.pumping.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.pumping.data.*
import app.pumping.domain.*

internal data class PhaseInput(val id: String, val kind: PhaseKind, val seconds: Long, val text: String, val dirty: Boolean = false)
internal fun SessionPhase.input() = PhaseInput(id, kind, seconds, java.math.BigDecimal(seconds).divide(java.math.BigDecimal(60), 9, java.math.RoundingMode.CEILING).stripTrailingZeros().toPlainString())
internal val PhaseInputSaver = listSaver<List<PhaseInput>, Any>(
    save = { rows -> rows.flatMap { listOf(it.id, it.kind.name, it.seconds, it.text, it.dirty) } },
    restore = { values -> values.chunked(5).map { PhaseInput(it[0] as String, PhaseKind.valueOf(it[1] as String), it[2] as Long, it[3] as String, it[4] as Boolean) } },
)
internal fun List<PhaseInput>.records(sessionId: String) = mapIndexed { index, p ->
    SessionPhase(p.id, sessionId, index, p.kind, p.seconds)
}

@androidx.compose.runtime.Composable
internal fun PowerTimingEditor(
    rows: List<PhaseInput>,
    input: (String, String) -> Unit,
    commit: () -> Unit,
    change: (List<PhaseInput>) -> Unit,
    preset: () -> Unit,
    firstPumpSeconds: Long,
) {
    Text("Intervals run consecutively in the order shown.")
    TextButton(preset, Modifier.testTag("power-preset")) { Text("Use 20 / 10 / 10 / 10 / 10 preset") }
    rows.forEachIndexed { index, p ->
        CardBlock {
            Text("Interval ${index + 1}")
            Choices(listOf("Pump", "Rest"), if (p.kind == PhaseKind.PUMP) "Pump" else "Rest") { kind ->
                change(rows.map { if (it.id == p.id) it.copy(kind = if (kind == "Pump") PhaseKind.PUMP else PhaseKind.REST) else it })
            }
            Field("Interval ${index + 1} minutes", p.text, { input(p.id, it) }, true, onCommit = commit)
            Row {
                TextButton({ change(rows.toMutableList().apply { add(index - 1, removeAt(index)) }) }, enabled = index > 0,
                    modifier = Modifier.testTag("phase-up-$index")) { Text("Up") }
                TextButton({ change(rows.toMutableList().apply { add(index + 1, removeAt(index)) }) }, enabled = index < rows.lastIndex,
                    modifier = Modifier.testTag("phase-down-$index")) { Text("Down") }
                TextButton({ change(rows.filterNot { it.id == p.id }) }, Modifier.testTag("phase-remove-$index")) { Text("Remove") }
            }
        }
    }
    Row {
        TextButton({
            val seconds = if (rows.isEmpty()) firstPumpSeconds else 600L
            change(rows + PhaseInput(newId(), PhaseKind.PUMP, seconds, (seconds / 60.0).toString()))
        }, enabled = rows.size < 200) { Text("Add pump interval") }
        TextButton({ change(rows + PhaseInput(newId(), PhaseKind.REST, 300L, "5.0")) }, enabled = rows.size < 200) { Text("Add rest interval") }
    }
    if (rows.isEmpty()) Text("No intervals saved. Enabling Power Pump does not assume a routine.")
}
