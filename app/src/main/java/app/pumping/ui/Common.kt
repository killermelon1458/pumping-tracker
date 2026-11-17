package app.pumping.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import app.pumping.data.Settings
import app.pumping.data.SettingsStore
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
    onCommit: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    var hadFocus by remember { mutableStateOf(false) }
    val interactive = enabled && LocalEditorInteraction.current
    val canCommit by rememberUpdatedState(interactive && LocalEditorCommit.current)
    val latestCommit by rememberUpdatedState(onCommit)
    OutlinedTextField(
        value,
        onChange,
        modifier.fillMaxWidth().testTag(label).onFocusChanged {
            if (hadFocus && !it.isFocused && canCommit) latestCommit?.invoke()
            hadFocus = it.isFocused
        },
        label = { Text(label) },
        enabled = interactive,
        singleLine = lines == 1,
        minLines = lines,
        shape = RoundedCornerShape(18.dp),
        keyboardOptions =
            KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                imeAction = if (onCommit != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = if (onCommit == null) KeyboardActions.Default
            else KeyboardActions(onDone = { if (canCommit) latestCommit?.invoke() }),
    )
}

@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, detail: String? = null, enabled: Boolean = true) {
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
        Switch(checked, onChange, Modifier.testTag(label), enabled = enabled && LocalEditorInteraction.current)
    }
}

@Composable
fun Choices(values: List<String>, selected: String, change: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val interactive = LocalEditorInteraction.current
        values.forEach { FilterChip(selected == it, { change(it) }, label = { Text(it) }, enabled = interactive) }
    }
}

@Composable
internal fun EditorLayout(
    title: String,
    close: () -> Unit,
    save: () -> Unit,
    enabled: Boolean = true,
    inputUnit: String? = null,
    onUnitToggle: (() -> Unit)? = null,
    unitEnabled: Boolean = true,
    layout: EditorLayoutState? = null,
    customizationStore: SettingsStore? = layout?.store,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = layout?.scroll ?: rememberScrollState()
    val interactive = layout?.let { it.loaded && !it.editing } ?: true
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxWidth().testTag("editor-sticky-header")) {
            if (inputUnit != null && onUnitToggle != null)
                EditorHeader(title, inputUnit, close, onUnitToggle, save, enabled && interactive, unitEnabled && interactive)
            else
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(close) { Text("Cancel") }
                    Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Button(save, enabled = enabled && interactive) { Text("Save") }
                }
            if (layout == null || layout.editing) customizationStore?.let { EditorLayoutToolbar(it, layout) }
        }
        Box(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { layout?.viewport = it.boundsInWindow() }) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).testTag("editor-scroll-content"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                content()
                if (layout != null && !layout.editing) customizationStore?.let {
                    Column(Modifier.fillMaxWidth().testTag("editor-customization-footer")) {
                        EditorLayoutToolbar(it, layout)
                    }
                }
            }
        }
    }
}

@Composable
fun Confirm(title: String, text: String, cancel: () -> Unit, accept: () -> Unit) {
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(accept) { Text("Confirm") } },
        dismissButton = { TextButton(cancel, Modifier.testTag("confirm-cancel")) { Text("Cancel") } },
    )
}
