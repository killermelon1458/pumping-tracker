package app.pumping.ui

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.View
import android.view.ViewGroup
import android.widget.TimePicker
import android.content.res.Configuration
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.widget.NumberPicker
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import app.pumping.R
import app.pumping.data.Settings
import app.pumping.domain.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
internal fun SessionTimestamp(
    label: String, value: Long, settings: Settings, expanded: Boolean, enabled: Boolean,
    openDate: () -> Unit, openTime: () -> Unit, cancelTime: () -> Unit, change: (Long) -> Unit,
) {
    val context = LocalContext.current
    val interactive = enabled && LocalEditorInteraction.current
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(change)
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    val z = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault())
    val tag = label.lowercase(java.util.Locale.ROOT)
    Column(Modifier.fillMaxWidth().testTag("timestamp-$tag").semantics { stateDescription = dateTime(value, settings) }) {
        FlowRow(verticalArrangement = Arrangement.Center) {
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) { Text("$label:") }
            TextButton(onClick = {
                openDate()
                calendarOpen = true
            }, enabled = interactive, modifier = Modifier.testTag("$tag-date").semantics { contentDescription = "$label date" }) {
                Text(z.format(DateTimeFormatter.ofPattern("d MMM yyyy")))
            }
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { Text("·") }
            TextButton(openTime, enabled = interactive, modifier = Modifier.testTag("$tag-time").semantics { contentDescription = "$label time" }) {
                Text(clock(value, settings))
            }
        }
        if (expanded) key(label) {
            EditorTimePicker(label, value, settings, cancelTime) { hour, minute ->
                latestChange(editorTime(latestValue, hour, minute, ZoneId.systemDefault()))
                cancelTime()
            }
        }
    }
    if (calendarOpen) DisposableEffect(context) {
        val initial = Instant.ofEpochMilli(latestValue).atZone(ZoneId.systemDefault())
        val dialog = DatePickerDialog(context, { _, year, month, day ->
            latestChange(editorDate(latestValue, LocalDate.of(year, month + 1, day), ZoneId.systemDefault()))
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
        dialog.setOnDismissListener { calendarOpen = false }
        dialog.show()
        onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
    }

}

/** Both presentations share this saved draft. Only Done invokes the editor's timestamp callback. */
@Stable
internal class TimePickerDraft(hour: Int, minute: Int) {
    var hour by mutableIntStateOf(hour)
    var minute by mutableIntStateOf(minute)
}
private val TimePickerDraftSaver = listSaver<TimePickerDraft, Int>(
    save = { listOf(it.hour,it.minute) }, restore = { TimePickerDraft(it[0],it[1]) },
)
@Composable
private fun rememberTimePickerDraft(initial: Long): TimePickerDraft = rememberSaveable(saver = TimePickerDraftSaver) {
    val z = Instant.ofEpochMilli(initial).atZone(ZoneId.systemDefault())
    TimePickerDraft(z.hour,z.minute)
}

@Composable
private fun EditorTimePicker(label: String, initial: Long, settings: Settings,
    cancel: () -> Unit, done: (Int,Int) -> Unit) {
    val draft = rememberTimePickerDraft(initial)
    when (settings.timePickerStyle) {
        TimePickerStyle.CLOCK_FACE -> if (LocalEditorInteraction.current)
            ClockFacePicker(label,draft,settings.clock24,settings.dark,cancel,done)
        TimePickerStyle.SCROLL_WHEELS -> TimeWheelPicker(label,initial,settings.clock24,settings.dark,cancel,done,draft)
    }
}

/** Explicit clock-mode theme prevents an OEM default from substituting spinner wheels. */
@Composable
private fun ClockFacePicker(label: String, draft: TimePickerDraft, clock24: Boolean, dark: Boolean,
    cancel: () -> Unit, done: (Int,Int) -> Unit) {
    val context = LocalContext.current
    val latestDone by rememberUpdatedState(done)
    val latestCancel by rememberUpdatedState(cancel)
    val compact = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    DisposableEffect(context,clock24,dark,compact) {
        var completed = false
        val dialog = TimePickerDialog(context, if (dark) R.style.PumpingClockDark else R.style.PumpingClockLight,
            { _, hour, minute ->
                if (!completed) {
                    completed = true
                    draft.hour = hour; draft.minute = minute
                    latestDone(hour,minute)
                }
            }, draft.hour,draft.minute,clock24)
        // The native landscape clock already fills the short edge. Its stock, title-free
        // arrangement keeps Done/Cancel fully visible at larger font scales.
        if (!compact) dialog.setTitle("$label time")
        dialog.setOnDismissListener {
            if (!completed) { completed = true; latestCancel() }
        }
        dialog.show()
        dialog.window?.decorView?.let { ViewCompat.setAccessibilityPaneTitle(it,"$label time") }
        dialog.getButton(TimePickerDialog.BUTTON_POSITIVE).setText(R.string.time_picker_done)
        // Public view traversal, not reflection/private resource IDs. Native direct entry and
        // AM/PM update the same temporary draft, including through activity recreation.
        dialog.window?.decorView?.let { findClock(it) }?.setOnTimeChangedListener { _, hour, minute ->
            draft.hour = hour; draft.minute = minute
        }
        onDispose { completed = true; dialog.setOnDismissListener(null); dialog.dismiss() }
    }
}
private fun findClock(view: View): TimePicker? {
    if (view is TimePicker) return view
    if (view is ViewGroup) for (index in 0 until view.childCount) {
        findClock(view.getChildAt(index))?.let { return it }
    }
    return null
}

/** Temporary wheel values; the editor sees exactly one edit on Done, none on Cancel. */
@Composable
internal fun TimeWheelPicker(label: String, initial: Long, clock24: Boolean, dark: Boolean,
    cancel: () -> Unit, done: (Int, Int) -> Unit, sharedDraft: TimePickerDraft? = null) {
    val interactive = LocalEditorInteraction.current
    val draft = sharedDraft ?: rememberTimePickerDraft(initial)
    val hour = draft.hour
    val minute = draft.minute
    val wheels = remember { mutableMapOf<String, NumberPicker>() }
    val scrolling = remember { mutableStateMapOf<String, Boolean>() }
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val modal = interactive && (maxWidth < 240.dp || fontScale > 1.3f ||
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || windowHeight < 480.dp)
        val wheelHeight = (108 * fontScale.coerceAtLeast(1f)).dp
        val content: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().height(wheelHeight).testTag("time-wheels").semantics {
                stateDescription = "%02d:%02d".format(java.util.Locale.ROOT, hour, minute)
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NativeWheel("hour", if (clock24) 0 else 1, if (clock24) 23 else 12,
                    if (clock24) hour else (hour % 12).let { if (it == 0) 12 else it }, clock24, dark,
                    Modifier.weight(1f).fillMaxHeight(), wheels, { scrolling["hour"] = it }) { old, next ->
                    draft.hour = wheelHour24(draft.hour, old, next, clock24)
                }
                NativeWheel("minute", 0, 59, minute, true, dark,
                    Modifier.weight(1f).fillMaxHeight(), wheels, { scrolling["minute"] = it }) { _, next -> draft.minute = next }
                if (!clock24) TextButton({ draft.hour = (draft.hour + 12) % 24 }, enabled = interactive, modifier = Modifier.widthIn(min = 56.dp).testTag("time-period").semantics { contentDescription = "AM/PM"; stateDescription = if (hour < 12) "AM" else "PM" },
                    content = { Text(if (hour < 12) "AM" else "PM") })
            }
        }
        val confirm: () -> Unit = {
            // Native direct typing is validated on focus loss, before applying the temporary value.
            wheels.values.forEach { it.clearFocus() }
            done(draft.hour, draft.minute)
        }
        val ready = scrolling.values.none { it }
        if (modal) AlertDialog(onDismissRequest = cancel,
            title = { Text("$label time") }, text = content,
            dismissButton = { TextButton(cancel, Modifier.testTag("time-cancel"), enabled = interactive) { Text("Cancel") } },
            confirmButton = { TextButton(confirm, Modifier.testTag("time-done"), enabled = ready && interactive) { Text("Done") } })
        else Column(Modifier.fillMaxWidth().testTag("inline-time-picker")) {
            content()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(cancel, Modifier.testTag("time-cancel"), enabled = interactive) { Text("Cancel") }
                TextButton(confirm, Modifier.testTag("time-done"), enabled = ready && interactive) { Text("Done") }
            }
        }
    }
}

// The listener only controls parent interception; native NumberPicker handles clicks/accessibility.
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun NativeWheel(name: String, min: Int, max: Int, value: Int, padded: Boolean, dark: Boolean,
    modifier: Modifier, wheels: MutableMap<String, NumberPicker>, scrolling: (Boolean) -> Unit,
    changed: (Int, Int) -> Unit) {
    val latestChanged by rememberUpdatedState(changed)
    val latestScrolling by rememberUpdatedState(scrolling)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val interactive = LocalEditorInteraction.current
    key(dark, min, max) {
        AndroidView(modifier = modifier, factory = { context ->
            NumberPicker(ContextThemeWrapper(context, if (dark) R.style.PumpingWheelDark else R.style.PumpingWheelLight)).apply {
                tag = "wheel-$name"
                contentDescription = if (name == "hour") "Hour" else "Minute"
                minValue = min; maxValue = max; wrapSelectorWheel = true
                if (padded) setFormatter { "%02d".format(java.util.Locale.ROOT, it) }
                this.value = value
                descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                if (Build.VERSION.SDK_INT >= 29) setTextColor(textColor)
                setOnValueChangedListener { _, old, next -> latestChanged(old, next) }
                setOnScrollListener { _, state -> latestScrolling(state != NumberPicker.OnScrollListener.SCROLL_STATE_IDLE) }
                setOnTouchListener { view, event ->
                    view.parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                    false
                }
                latestScrolling(false)
                wheels[name] = this
            }
        }, update = { picker ->
            picker.isEnabled = interactive
            picker.descendantFocusability = if (interactive) android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS else android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            picker.importantForAccessibility = if (interactive) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (picker.value != value) picker.value = value
            if (Build.VERSION.SDK_INT >= 29) picker.setTextColor(textColor)
        })
    }
}
