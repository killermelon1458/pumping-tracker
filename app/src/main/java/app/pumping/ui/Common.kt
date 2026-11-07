package app.pumping.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import app.pumping.data.Settings
import app.pumping.domain.*
import java.time.*
import java.time.format.DateTimeFormatter

val Pink = Color(0xFFEA63A6)
val Blue = Color(0xFF5EB9EB)

@Composable
fun PumpingTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            if (dark)
                darkColorScheme(
                    primary = Pink,
                    secondary = Blue,
                    background = Color(0xFF1A1A1A),
                    surface = Color(0xFF202022),
                    surfaceVariant = Color(0xFF2D2D2D),
                    onSurface = Color(0xFFE5DFE1),
                    onSurfaceVariant = Color(0xFFC3BFC2),
                )
            else
                lightColorScheme(
                    primary = Color(0xFF9F2864),
                    secondary = Color(0xFF12688F),
                    background = Color(0xFFFFF8FA),
                    surface = Color.White,
                    surfaceVariant = Color(0xFFF1E6EC),
                ),
        content = content,
    )
}

fun clock(time: Long, s: Settings): String =
    Instant.ofEpochMilli(time)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (s.clock24) "HH:mm" else "h:mm a"))

fun dateTime(time: Long, s: Settings): String =
    "${dayOf(time).format(DateTimeFormatter.ofPattern("d MMM yyyy"))} · ${clock(time,s)}"

fun gap(ms: Long): String {
    val minutes = maxOf(0, ms / 60000)
    return if (minutes >= 1440) "${minutes/1440}d ${minutes%1440/60}h"
    else if (minutes >= 60) "${minutes/60}h ${minutes%60}m" else "${minutes}m"
}

fun quantity(value: Long, s: Settings) = "${Volume.format(value,s.displayUnit)} ${s.displayUnit}"

@Composable
fun CardBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun Heading(text: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        actions()
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    numeric: Boolean = false,
    modifier: Modifier = Modifier,
    lines: Int = 1,
) {
    OutlinedTextField(
        value,
        onChange,
        modifier.fillMaxWidth().testTag(label),
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        shape = RoundedCornerShape(18.dp),
        keyboardOptions =
            KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
    )
}

@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, detail: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (detail != null)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        Switch(checked, onChange, Modifier.testTag(label))
    }
}

@Composable
fun Choices(values: List<String>, selected: String, change: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { FilterChip(selected == it, { change(it) }, label = { Text(it) }) }
    }
}

@Composable
fun Timestamp(label: String, value: Long, s: Settings, change: (Long) -> Unit) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            val z = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault())
            DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    change(
                                        LocalDate.of(year, month + 1, day)
                                            .atTime(hour, minute)
                                            .atZone(ZoneId.systemDefault())
                                            .toInstant()
                                            .toEpochMilli()
                                    )
                                },
                                z.hour,
                                z.minute,
                                s.clock24,
                            )
                            .show()
                    },
                    z.year,
                    z.monthValue - 1,
                    z.dayOfMonth,
                )
                .show()
        }
    ) {
        Text("$label: ${dateTime(value,s)}")
    }
}

@Composable
fun EditorLayout(
    title: String,
    close: () -> Unit,
    save: () -> Unit,
    enabled: Boolean = true,
    inputUnit: String? = null,
    onUnitToggle: (() -> Unit)? = null,
    unitEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        if (inputUnit != null && onUnitToggle != null)
            EditorHeader(title, inputUnit, close, onUnitToggle, save, enabled, unitEnabled)
        else
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(close) { Text("Cancel") }
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Button(save, enabled = enabled) { Text("Save") }
            }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
fun Confirm(title: String, text: String, cancel: () -> Unit, accept: () -> Unit) {
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(accept) { Text("Confirm") } },
        dismissButton = { TextButton(cancel) { Text("Cancel") } },
    )
}
