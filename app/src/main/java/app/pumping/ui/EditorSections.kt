package app.pumping.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.pumping.data.SettingsStore
import app.pumping.domain.*
import kotlinx.coroutines.*

internal val LocalEditorInteraction = staticCompositionLocalOf { true }
internal val LocalEditorCommit = staticCompositionLocalOf { true }

@Stable
internal class EditorLayoutState(
    val kind: EditorKind,
    val store: SettingsStore,
    private val scope: CoroutineScope,
    private val saved: MutableState<String?>,
    private val draft: MutableState<String>,
    private val mode: MutableState<Boolean>,
    val scroll: ScrollState,
) {
    val loaded get() = saved.value != null
    val editing get() = mode.value
    val arrangement get() = EditorArrangement.decode(kind, if (editing) draft.value else saved.value)
    var saving by mutableStateOf(false); private set
    var error by mutableStateOf(false); private set
    var viewport by mutableStateOf(Rect.Zero)
    var draggingSection by mutableStateOf<String?>(null)
    var dragPointerY by mutableFloatStateOf(0f)
    var pickerMenuOpen by mutableStateOf(false)
    fun begin() { if (loaded && !saving) { draft.value = arrangement.encode(); mode.value = true; error = false } }
    fun cancel() { if (!saving) { mode.value = false; error = false } }
    fun reset() { draft.value = EditorArrangement.default(kind).encode() }
    fun move(id: String, index: Int) { draft.value = arrangement.move(id,index).encode() }
    fun show(id: String, visible: Boolean) { draft.value = arrangement.visible(kind,id,visible).encode() }
    fun done() {
        if (saving) return
        val result = arrangement
        scope.launch {
            saving = true
            try {
                store.saveEditorLayout(kind,result)
                saved.value = result.encode()
                mode.value = false
                error = false
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = true
            } finally { saving = false }
        }
    }
}

@Composable
internal fun rememberEditorLayout(kind: EditorKind, store: SettingsStore): EditorLayoutState {
    val saved = rememberSaveable(kind.key) { mutableStateOf<String?>(null) }
    val draft = rememberSaveable(kind.key) { mutableStateOf("") }
    val mode = rememberSaveable(kind.key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    LaunchedEffect(store,kind) { store.editorLayout(kind).collect { saved.value = it.encode() } }
    return remember(store,kind) { EditorLayoutState(kind,store,scope,saved,draft,mode,scroll) }
}

@Composable
internal fun EditorLayoutToolbar(store: SettingsStore, state: EditorLayoutState?) {
    if (state != null && !state.loaded) return
    val style by store.timePickerStyle.collectAsState(initial = TimePickerStyle.CLOCK_FACE)
    var localMenu by remember { mutableStateOf(false) }
    val menu = state?.pickerMenuOpen ?: localMenu
    fun showMenu(show: Boolean) { if (state != null) state.pickerMenuOpen = show else localMenu = show }
    var styleError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        if (state?.editing != true) {
            Box {
                TextButton({ showMenu(true) }, Modifier.testTag("time-picker-style").semantics {
                    contentDescription = "Time picker style"
                    stateDescription = style.label
                }) { Text("Time picker: ${style.label}") }
                DropdownMenu(menu, { showMenu(false) }) {
                    TimePickerStyle.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            leadingIcon = { RadioButton(style == option, onClick = null) },
                            modifier = Modifier.testTag("picker-style-${option.name}"),
                            onClick = {
                                showMenu(false)
                                scope.launch {
                                    try { store.saveTimePickerStyle(option); styleError = false }
                                    catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        styleError = true
                                    }
                                }
                            },
                        )
                    }
                }
            }
            if (state != null) TextButton(state::begin, Modifier.testTag("edit-layout")) { Text("Edit Layout") }
        } else {
            TextButton(state::reset, Modifier.testTag("layout-reset"), enabled = !state.saving) { Text("Reset to Default") }
            TextButton(state::cancel, Modifier.testTag("layout-cancel"), enabled = !state.saving) { Text("Cancel") }
            TextButton(state::done, Modifier.testTag("layout-done"), enabled = !state.saving && state.draggingSection == null) { Text("Done") }
        }
    }
    if (state?.editing == true) Text("Hold a handle to drag, or use Up / Down. Required sections stay visible.",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    if (state?.error == true) Text("Could not save layout. Try again.", color = MaterialTheme.colorScheme.error)
    if (styleError) Text("Could not save picker style. Try again.", color = MaterialTheme.colorScheme.error)
}

/** Keyed presentation only: form values, drafts, authority and validation remain editor-owned. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun EditorSections(state: EditorLayoutState, forced: Set<String> = emptySet(),
    content: @Composable ColumnScope.(String) -> Unit) {
    val kind = state.kind
    val arrangement = state.arrangement
    val bounds = remember { mutableMapOf<String,Rect>() }
    val handles = remember { mutableMapOf<String,Rect>() }
    val host = remember { arrayOf(androidx.compose.ui.geometry.Offset.Zero) }
    var centerDelta by remember { mutableFloatStateOf(0f) }
    var dragCenter by remember { mutableFloatStateOf(0f) }
    var lastMove by remember { mutableLongStateOf(0L) }
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    val speed = with(LocalDensity.current) { 12.dp.toPx() }
    fun moveIfCrossed(now: Long) {
        if (now - lastMove < 220_000_000L) return
        val id = state.draggingSection ?: return
        val order = state.arrangement.order
        val index = order.indexOf(id)
        val previous = order.getOrNull(index-1)
        val next = order.getOrNull(index+1)
        if (previous != null && bounds[previous]?.let { dragCenter < it.center.y } == true) {
            state.move(id,index-1); lastMove = now
        } else if (next != null && bounds[next]?.let { dragCenter > it.center.y } == true) {
            state.move(id,index+1); lastMove = now
        }
    }
    // Use the editor's one scroll container. No nested scrolling or wheel gesture interception.
    LaunchedEffect(state.draggingSection) {
        while (state.draggingSection != null) {
            withFrameNanos { }
            val viewport = state.viewport
            val delta = when {
                state.dragPointerY < viewport.top + edge -> -speed * ((viewport.top + edge - state.dragPointerY) / edge).coerceIn(0f,1f)
                state.dragPointerY > viewport.bottom - edge -> speed * ((state.dragPointerY - viewport.bottom + edge) / edge).coerceIn(0f,1f)
                else -> 0f
            }
            if (delta != 0f) state.scroll.scrollBy(delta)
            moveIfCrossed(System.nanoTime())
        }
    }
    LaunchedEffect(state.editing) { if (!state.editing) state.draggingSection = null }
    LookaheadScope {
        val gesture = if (state.editing) Modifier.pointerInput(kind.key) {
            // The detector stays on this parent when a child moves. Only a handle starts a drag.
            detectDragGesturesAfterLongPress(
                onDragStart = { point ->
                    val windowPoint = host[0] + point
                    val id = state.arrangement.order.firstOrNull { handles[it]?.contains(windowPoint) == true }
                    if (id != null && !state.saving) {
                        state.dragPointerY = windowPoint.y
                        centerDelta = (bounds[id]?.center?.y ?: windowPoint.y) - windowPoint.y
                        dragCenter = windowPoint.y + centerDelta
                        lastMove = 0L
                        state.draggingSection = id
                    }
                },
                onDragEnd = { state.draggingSection = null },
                onDragCancel = { state.draggingSection = null },
                onDrag = { change, delta -> if (state.draggingSection != null) {
                    change.consume(); state.dragPointerY += delta.y
                    dragCenter = state.dragPointerY + centerDelta
                    moveIfCrossed(System.nanoTime())
                } },
            )
        } else Modifier
        Column(Modifier.onGloballyPositioned { host[0] = it.positionInWindow() }.then(gesture),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            arrangement.order.forEachIndexed { index, id ->
                val section = kind.sections.first { it.id == id }
                val hidden = id in arrangement.hidden
                if (state.editing || !hidden || id in forced) key(kind.key,id) {
                    val lifted = state.draggingSection == id
                    val placement = if (state.editing) Modifier.animateBounds(this@LookaheadScope, boundsTransform = { _, _ -> if (lifted) snap() else tween(160) }) else Modifier
                    Box(Modifier.fillMaxWidth().then(placement)
                        .onGloballyPositioned {
                            val p = it.positionInWindow()
                            bounds[id] = Rect(p.x,p.y,p.x+it.size.width,p.y+it.size.height)
                        }
                        .zIndex(if (lifted) 1f else 0f)
                        .testTag("section-${kind.key}-$id")) {
                    Column(Modifier.fillMaxWidth()
                        .graphicsLayer {
                            // Geometry is not Compose state: measuring a lifted preview must not
                            // invalidate its own layout. Scroll invalidates this layer only.
                            state.scroll.value
                            if (lifted) translationY = dragCenter - (bounds[id]?.center?.y ?: dragCenter)
                        }
                        .shadow(if (lifted) 12.dp else 0.dp, RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(20.dp))) {
                        if (state.editing) {
                            Row(Modifier.fillMaxWidth().testTag("layout-row-$id"), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(!hidden, { state.show(id,it) },
                                    Modifier.size(48.dp).testTag("layout-show-$id").semantics { contentDescription = "Show ${section.title}" },
                                    enabled = !section.required && !state.saving)
                                Text(section.title, Modifier.weight(1f).testTag("layout-name-$id"),
                                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton({ state.move(id,index-1) }, Modifier.size(48.dp).testTag("layout-up-$id").semantics { contentDescription = "Move ${section.title} up" },
                                    enabled = index > 0 && !state.saving) { Icon(Icons.Default.ArrowUpward, contentDescription = null) }
                                IconButton({ state.move(id,index+1) }, Modifier.size(48.dp).testTag("layout-down-$id").semantics { contentDescription = "Move ${section.title} down" },
                                    enabled = index < arrangement.order.lastIndex && !state.saving) { Icon(Icons.Default.ArrowDownward, contentDescription = null) }
                                Box(Modifier.size(48.dp).testTag("layout-handle-$id")
                                    .semantics { contentDescription = "Reorder ${section.title}" }
                                    .onGloballyPositioned {
                                        val p = it.positionInWindow()
                                        handles[id] = Rect(p.x,p.y,p.x+it.size.width,p.y+it.size.height)
                                    }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        if (!state.editing && hidden && id in forced) Text("Recorded information", style = MaterialTheme.typography.labelSmall)
                        Box {
                            CompositionLocalProvider(LocalEditorInteraction provides !state.editing,
                                LocalEditorCommit provides (!state.editing && !state.pickerMenuOpen)) {
                                Column(if (state.editing) Modifier.clearAndSetSemantics { } else Modifier) { content(id) }
                            }
                            if (state.editing) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
                        }
                    }
                    }
                }
            }
        }
    }
}
