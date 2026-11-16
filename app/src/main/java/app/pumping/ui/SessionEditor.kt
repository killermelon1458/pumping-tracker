package app.pumping.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pumping.*
import app.pumping.data.*
import app.pumping.domain.*

@Composable
fun SessionEditor(
    vm: PumpViewModel,
    s: Settings,
    existing: Session?,
    timer: TimerState?,
    notification: () -> Unit,
    close: () -> Unit,
) {
    val pumps by vm.pumps.collectAsStateWithLifecycle()
    var selectedPump by rememberSaveable { mutableStateOf(existing?.pumpProfileId) }
    var pumpInitialized by rememberSaveable { mutableStateOf(existing != null) }
    LaunchedEffect(Unit) {
        if (!pumpInitialized) {
            val last = vm.repo.defaultPumpProfile()
            if (!pumpInitialized) selectedPump = last
            pumpInitialized = true
        }
    }
    var inputUnit by rememberSaveable { mutableStateOf(s.inputUnit) }
    val draftId = rememberSaveable {
        existing?.id ?: timer?.id?.takeIf { it.isNotEmpty() } ?: newId()
    }
    val initialEnd = remember { timer?.draftEnd?.takeIf { it > 0 } ?: System.currentTimeMillis() }
    val initialSeconds = remember {
        existing?.activeSeconds ?: timer?.accumulated?.div(1000)?.coerceAtLeast(1) ?: 1200L
    }
    var start by rememberSaveable {
        mutableLongStateOf(
            existing?.start
                ?: timer?.start?.takeIf { it > 0 }
                ?: (initialEnd - initialSeconds * 1000)
        )
    }
    var end by rememberSaveable { mutableLongStateOf(existing?.end ?: initialEnd) }
    var duration by rememberSaveable { mutableStateOf(durationMinutes(initialSeconds)) }
    var committedSeconds by rememberSaveable { mutableLongStateOf(initialSeconds) }
    var powerName by rememberSaveable { mutableStateOf((existing?.powerStatus ?: PowerPumpStatus.NORMAL).name) }
    var elapsedKnown by rememberSaveable { mutableStateOf(existing?.elapsedKnown ?: true) }
    var phaseInputs by rememberSaveable(stateSaver = PhaseInputSaver) { mutableStateOf(emptyList<PhaseInput>()) }
    var phaseLoaded by rememberSaveable { mutableStateOf(existing == null) }
    var phaseActive by rememberSaveable { mutableLongStateOf(initialSeconds) }
    var routineEstablished by rememberSaveable { mutableStateOf(false) }
    var lastTimestamp by rememberSaveable { mutableStateOf("END") }
    var timerRemainder by rememberSaveable { mutableLongStateOf(if (timer != null || existing?.mode == "TIMER") (end - start) % 1000 else 0) }
    var pendingPhases by remember { mutableStateOf<List<PhaseInput>?>(null) }
    var timingExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingPowerName by remember { mutableStateOf<String?>(null) }
    val timedSession = timer != null || existing?.mode == "TIMER"
    LaunchedEffect(existing?.id) {
        if (!phaseLoaded) {
            val saved = vm.repo.dao.phases(requireNotNull(existing).id)
            phaseInputs = saved.map { it.input() }
            if (saved.isNotEmpty()) {
                val totals = phaseTotals(saved)
                committedSeconds = totals.elapsed
                duration = durationMinutes(totals.elapsed)
                phaseActive = totals.active
                routineEstablished = true

            }
            phaseLoaded = true
        }
    }
    val hasDetails = phaseInputs.isNotEmpty()
    val separateActiveSpan = !hasDetails && elapsedKnown && end - start > committedSeconds * 1000
    var historyNames by rememberSaveable { mutableStateOf(listOf("END", "DURATION")) }
    var timingError by rememberSaveable { mutableStateOf<String?>(null) }
    var futureWarning by rememberSaveable { mutableStateOf(false) }
    fun commitTime(field: TimeField, value: Long, preserveDurationDraft: Boolean = false): Boolean {
        val unchanged = when (field) {
            TimeField.START -> value == start
            TimeField.END -> value == end
            TimeField.DURATION -> value == committedSeconds
        }
        if (unchanged) {
            timingError = null
            return true
        }
        return try {
            if (phaseInputs.isNotEmpty()) {
                val elapsed = Math.addExact(Math.multiplyExact(phaseTotals(phaseInputs.records(draftId)).elapsed, 1000), timerRemainder)
                val next = RoutineTiming(start, end, TimeField.valueOf(lastTimestamp)).edit(field, value, elapsed)
                start = next.start; end = next.end
                lastTimestamp = next.timestamp.name
                historyNames = (historyNames.filterNot { it == field.name } + field.name).takeLast(2)
            } else if (!timedSession) {
                val next = QuickLogTiming(start, end, committedSeconds, historyNames.map(TimeField::valueOf)).edit(field, value)
                start = next.start; end = next.end; committedSeconds = next.seconds
                if (!preserveDurationDraft) duration = durationMinutes(next.seconds)
                historyNames = next.history.map { it.name }
                if (field != TimeField.DURATION) lastTimestamp = field.name
                if (powerName == PowerPumpStatus.POWER.name || !elapsedKnown) elapsedKnown = false
            } else {
                // Preserve separately recorded timer pauses and active time in basic mode.
                when (field) {
                    TimeField.START -> { start = value; lastTimestamp = field.name }
                    TimeField.END -> { end = value; lastTimestamp = field.name }
                    TimeField.DURATION -> { committedSeconds = value; if (!preserveDurationDraft) duration = durationMinutes(value) }
                }
            }
            if (phaseInputs.isEmpty()) phaseActive = committedSeconds
            timingError = null
            true
        } catch (e: Exception) {
            timingError = e.message ?: "Invalid session interval"
            false
        }
    }
    fun commitDuration(): Boolean {
        // Validate the actual displayed draft even if focus/IME callbacks never happened.
        return try { commitTime(TimeField.DURATION, durationSeconds(duration), preserveDurationDraft = true) }
        catch (e: Exception) { timingError = "Enter a valid positive duration"; false }
    }
    fun applyPhases(nextInputs: List<PhaseInput>): Boolean {
        if (!commitDuration()) return false
        if (nextInputs.isEmpty()) {
            if (phaseInputs.isNotEmpty()) {
                if (!timedSession) {
                    val next = RoutineTiming(start, end, TimeField.valueOf(lastTimestamp)).resize(Math.multiplyExact(phaseActive, 1000))
                    start = next.start; end = next.end; elapsedKnown = false
                }
                committedSeconds = phaseActive
                duration = durationMinutes(phaseActive)
            }
            phaseInputs = emptyList()
            timingError = null
            return true
        }
        phaseInputs = nextInputs
        return try {
            val parsed = nextInputs.map { p -> if (p.dirty) {
                val seconds = durationSeconds(p.text)
                p.copy(seconds = seconds, dirty = false)
            } else p }
            phaseInputs = parsed
            val total = phaseTotals(parsed.records(draftId))
            if (timedSession && !routineEstablished) {
                require(total.active == phaseActive && (end - start) / 1000 == total.elapsed) {
                    "Intervals must match recorded timer active time and elapsed time."
                }
            }
            val next = RoutineTiming(start, end, TimeField.valueOf(lastTimestamp))
                .resize(Math.addExact(Math.multiplyExact(total.elapsed, 1000), timerRemainder))
            start = next.start; end = next.end
            committedSeconds = total.elapsed
            duration = durationMinutes(total.elapsed)
            phaseActive = total.active
            elapsedKnown = true
            routineEstablished = true

            timingError = null
            true
        } catch (e: Exception) {
            timingError = e.message ?: "Invalid timing intervals"
            false
        }
    }
    fun requestPhases(next: List<PhaseInput>) {
        if (phaseInputs.any { old -> next.none { it.id == old.id } }) pendingPhases = next
        else applyPhases(next)
    }
    var total by rememberSaveable {
        mutableStateOf(existing?.let { Volume.format(it.volume, inputUnit, 5) } ?: "")
    }
    var left by rememberSaveable {
        mutableStateOf(existing?.left?.let { Volume.format(it, inputUnit, 5) } ?: "")
    }
    var right by rememberSaveable {
        mutableStateOf(existing?.right?.let { Volume.format(it, inputUnit, 5) } ?: "")
    }
    var split by rememberSaveable { mutableStateOf(existing?.left != null) }
    var breastfedBefore by rememberSaveable {
        mutableStateOf((existing?.breastfedBefore ?: BreastfeedingStatus.UNKNOWN).name)
    }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    var zero by rememberSaveable { mutableStateOf(existing?.volume == 0L) }
    var stash by rememberSaveable { mutableStateOf(false) }
    var reminder by rememberSaveable { mutableStateOf(false) }
    fun referenceAt() = if (s.pumpReference == PumpReference.START) start else end
    var remindAt by rememberSaveable {
        mutableLongStateOf(nextReminder(referenceAt(), s.reminderMinutes))
    }
    var customReminder by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(start, end, s.pumpReference, s.reminderMinutes) {
        if (!customReminder) remindAt = nextReminder(referenceAt(), s.reminderMinutes)
    }
    var allocationValues by rememberSaveable { mutableStateOf(listOf("")) }
    var allocationLocations by rememberSaveable { mutableStateOf(listOf("Fridge")) }
    var discard by remember { mutableStateOf(false) }
    var initialSnapshot by rememberSaveable { mutableStateOf<EditorSnapshot?>(null) }
    var expandedTime by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var existingAllocation by rememberSaveable { mutableLongStateOf(0) }
    var allocationLoaded by rememberSaveable { mutableStateOf(existing == null) }
    LaunchedEffect(existing?.id) {
        existing?.let { existingAllocation = vm.repo.dao.allocated(it.id) }
        allocationLoaded = true
    }
    fun measured(): Long =
        if (zero) 0
        else if (split)
            Math.addExact(
                Volume.parse(left.ifBlank { "0" }, inputUnit),
                Volume.parse(right.ifBlank { "0" }, inputUnit),
            )
        else Volume.parse(total, inputUnit)
    val output = runCatching { measured() }.getOrNull()
    fun snapshot(): EditorSnapshot {
        // Compare normalized authoritative inputs, projecting any pending valid phase edit.
        var snapshotStart = start
        var snapshotEnd = end
        var snapshotSeconds = committedSeconds
        var snapshotDuration = normalizedSeconds(duration)
        if (phaseInputs.isNotEmpty()) runCatching {
            val parsed = phaseInputs.map { it.copy(seconds = durationSeconds(it.text)) }.records(draftId)
            val totals = phaseTotals(parsed)
            val projected = RoutineTiming(start, end, TimeField.valueOf(lastTimestamp))
                .resize(Math.addExact(Math.multiplyExact(totals.elapsed, 1000), timerRemainder))
            snapshotStart = projected.start; snapshotEnd = projected.end
            snapshotSeconds = totals.active; snapshotDuration = "derived:${totals.elapsed}"
        }
        val values = mutableListOf(snapshotStart.toString(), snapshotEnd.toString(), snapshotSeconds.toString(), snapshotDuration,
            normalizedVolume(total, inputUnit), normalizedVolume(left, inputUnit), normalizedVolume(right, inputUnit),
            split.toString(), zero.toString(), notes, breastfedBefore, selectedPump ?: "", powerName,
            stash.toString(), reminder.toString())
        // Default allocations/deadlines are derived, not independent edits. Keep hidden custom drafts.
        val available = runCatching { measured() }.getOrNull()?.let { maxOf(0, it - existingAllocation) }
        val defaultAllocation = allocationValues.size == 1 && allocationLocations == listOf("Fridge") &&
            (allocationValues.single().isEmpty() || runCatching { Volume.parse(allocationValues.single(), inputUnit) == available }.getOrDefault(false))
        values += if (defaultAllocation) listOf("default-allocation") else
            allocationValues.mapIndexed { index, text -> "${allocationLocations[index]}:${normalizedVolume(text,inputUnit)}" }
        values += if (!customReminder || remindAt / 60000 == nextReminder(referenceAt(), s.reminderMinutes) / 60000) "default-reminder" else "reminder:${remindAt / 60000}"
        values += phaseInputs.map { "${it.kind.name}:${normalizedSeconds(it.text)}" }
        return EditorSnapshot(values)
    }
    LaunchedEffect(pumpInitialized, phaseLoaded, allocationLoaded) {
        if (initialSnapshot == null && pumpInitialized && phaseLoaded && allocationLoaded) initialSnapshot = snapshot()
    }
    fun requestClose() {
        if (initialSnapshot == null || snapshot() == initialSnapshot) close() else discard = true
    }
    BackHandler {
        if (expandedTime != null) expandedTime = null else requestClose()
    }

    fun saveSession() {
        vm.launch {
            busy = true
            try {
                require(phaseInputs.isNotEmpty() || durationSeconds(duration) == committedSeconds) {
                    "Duration and session timing are inconsistent. Review the duration before saving."
                }
                val amount = measured()
                require(amount > 0 || zero) { "Enter output or select No milk expressed" }
                val seconds = if (hasDetails) phaseTotals(phaseInputs.records(draftId)).active else committedSeconds
                val session =
                    Session(
                        id = draftId,
                        start = start,
                        end = end,
                        activeSeconds = seconds,
                        volume = amount,
                        left =
                            if (split && !zero) Volume.parse(left.ifBlank { "0" }, inputUnit)
                            else null,
                        right =
                            if (split && !zero) Volume.parse(right.ifBlank { "0" }, inputUnit)
                            else null,
                        powerStatus = PowerPumpStatus.valueOf(powerName),
                        elapsedKnown = if (existing == null && powerName == PowerPumpStatus.POWER.name && !timedSession && !hasDetails) false else elapsedKnown,
                        pumpProfileId = selectedPump,
                        breastfedBefore = BreastfeedingStatus.valueOf(breastfedBefore),
                        notes = notes,
                        mode = existing?.mode ?: if (timer != null) "TIMER" else "MANUAL",
                        created = existing?.created ?: System.currentTimeMillis(),
                    )
                val allocations =
                    if (stash)
                        allocationValues.mapIndexed { i, v ->
                            Allocation(
                                Volume.parse(v, inputUnit),
                                allocationLocations[i],
                                s.days(allocationLocations[i]),
                            )
                        }
                    else emptyList()
                vm.save(session, allocations, if (reminder) remindAt else null, phaseInputs.records(draftId))
                vm.app.reminders.restore(vm.app)
                close()
            } finally {
                busy = false
            }
        }
    }
    fun requestSave() {
        if (!commitDuration()) return
        if (phaseInputs.isNotEmpty() && !applyPhases(phaseInputs)) return
        if (end <= start || committedSeconds <= 0 || committedSeconds > (end - start) / 1000) {
            timingError = "Active duration must fit inside start and end times"
            return
        }
        if (!customReminder) remindAt = nextReminder(referenceAt(), s.reminderMinutes)
        if (end > System.currentTimeMillis()) futureWarning = true else saveSession()
    }
    EditorLayout(
        if (existing == null) "Log Pump" else "Edit Pump",
        { requestClose() },
        { requestSave() },
        !busy && expandedTime == null && initialSnapshot != null && pumpInitialized && phaseLoaded && output != null && (output > 0 || zero),
        inputUnit = inputUnit,
        unitEnabled = !busy && initialSnapshot != null,
        onUnitToggle = {
            busy = true
            vm.launch {
                try {
                    val next = if (inputUnit == "oz") "mL" else "oz"
                    // Convert every draft first: invalid text leaves the draft and
                    // preference
                    // intact.
                    val converted =
                        (listOf(total, left, right) + allocationValues).map {
                            Volume.convertInput(it, inputUnit, next)
                        }
                    vm.store.string("inputUnit", next)
                    total = converted[0]
                    left = converted[1]
                    right = converted[2]
                    allocationValues = converted.drop(3)
                    inputUnit = next
                } finally {
                    busy = false
                }
            }
        },
    ) {
        if (initialSnapshot == null) {
            Text("Loading session…")
            return@EditorLayout
        }
        CardBlock {
            if (hasDetails) {
                Text("Pumping time: ${pumpElapsedText(phaseActive * 1000)}")
                Text("Total elapsed time: ${pumpElapsedText((end - start))}")
                Text("Pumping time excludes rests; total elapsed time includes them.", style = MaterialTheme.typography.bodySmall)
            } else {
                Field(
                    "Duration (minutes)",
                    duration,
                    { v ->
                        if (v != duration) {
                            // Preserve the user's exact text/cursor. Only valid values update timing.
                            duration = v
                            timingError = null
                            runCatching { durationSeconds(v) }.getOrNull()?.let {
                                commitTime(TimeField.DURATION, it, preserveDurationDraft = true)
                            }
                        }
                    },
                    true,
                    onCommit = {
                        if (commitDuration() && !customReminder)
                            remindAt = nextReminder(referenceAt(), s.reminderMinutes)
                    },
                    enabled = phaseLoaded,
                )
            }
            if (!hasDetails && ((!elapsedKnown) || (powerName == PowerPumpStatus.POWER.name && !timedSession))) Text("Duration is active pumping time; actual total elapsed time is not recorded.", style = MaterialTheme.typography.bodySmall)
            else if (separateActiveSpan && (powerName != PowerPumpStatus.POWER.name || timedSession)) Text("Recorded elapsed: ${pumpElapsedText(end - start)}. Active time excludes breaks.")
            SessionTimestamp("Start", start, s, expandedTime == "Start", phaseLoaded,
                openDate = { expandedTime = null },
                openTime = { expandedTime = if (expandedTime == "Start") null else "Start" },
                cancelTime = { expandedTime = null }) {
                if (commitDuration() && commitTime(TimeField.START, it) && !customReminder)
                    remindAt = nextReminder(referenceAt(), s.reminderMinutes)
            }
            SessionTimestamp("End", end, s, expandedTime == "End", phaseLoaded,
                openDate = { expandedTime = null },
                openTime = { expandedTime = if (expandedTime == "End") null else "End" },
                cancelTime = { expandedTime = null }) {
                if (commitDuration() && commitTime(TimeField.END, it) && !customReminder)
                    remindAt = nextReminder(referenceAt(), s.reminderMinutes)
            }
            timingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (timer != null)
                Text(
                    "Active duration excludes pauses. Start and end retain the full session span.",
                    style = MaterialTheme.typography.bodySmall,
                )
        }
        CardBlock {
            Toggle("Power Pump", powerName == PowerPumpStatus.POWER.name, { enabled ->
                val next = if (enabled) PowerPumpStatus.POWER.name else PowerPumpStatus.NORMAL.name
                if (!enabled && phaseInputs.isNotEmpty()) { pendingPowerName = next; pendingPhases = emptyList() }
                else {
                    powerName = next

                }
            }, if (powerName == PowerPumpStatus.UNKNOWN.name) "Not specified for this historical record" else null, enabled = phaseLoaded)
            if (existing != null && powerName != PowerPumpStatus.UNKNOWN.name) TextButton({
                if (phaseInputs.isNotEmpty()) { pendingPowerName = PowerPumpStatus.UNKNOWN.name; pendingPhases = emptyList() } else powerName = PowerPumpStatus.UNKNOWN.name
            }) { Text("Mode not specified") }
            if (powerName == PowerPumpStatus.POWER.name) {
                TextButton({ timingExpanded = !timingExpanded }, enabled = phaseLoaded) { Text(if (timingExpanded) "Hide timing details" else "Timing details (optional)") }
                if (timingExpanded) {
                    if (timedSession) Text("Intervals must match recorded timer active/elapsed totals; pauses will not be overwritten.")
                    PowerTimingEditor(phaseInputs,
                        input = { id, text -> phaseInputs = phaseInputs.map { if (it.id == id && it.text != text) it.copy(text = text, dirty = true) else it } },
                        commit = { if (applyPhases(phaseInputs) && !customReminder) remindAt = nextReminder(referenceAt(), s.reminderMinutes) },
                        change = { requestPhases(it) },
                        preset = { requestPhases(powerPreset(draftId).map { it.input() }) },
                        firstPumpSeconds = if (hasDetails) phaseActive else committedSeconds,
                    )
                    timingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            HorizontalDivider()
            Text("Breastfed before pumping?", style = MaterialTheme.typography.titleMedium)
            Choices(BreastfeedingStatus.entries.map { it.displayLabel() },
                BreastfeedingStatus.valueOf(breastfedBefore).displayLabel()) { label ->
                breastfedBefore = BreastfeedingStatus.entries.first { it.displayLabel() == label }.name
            }
            PumpSelector(pumps, selectedPump, existing?.pumpProfileId) {
                selectedPump = it
                pumpInitialized = true
            }
        }
        CardBlock {
            Toggle("No milk expressed", zero, { zero = it })
            if (!zero) {
                if (split) {
                    Field("Left Breast (${inputUnit})", left, { left = it }, true)
                    Field("Right Breast (${inputUnit})", right, { right = it }, true)
                    Text("Total: ${output?.let{quantity(it,s)} ?: "—"}")
                } else Field("Volume (${inputUnit})", total, { total = it }, true)
                Toggle("Track each breast separately", split, { split = it })
            }
        }
        CardBlock {
            if (existingAllocation > 0)
                Text(
                    "Previously stashed: ${quantity(existingAllocation,s)}. Existing bags keep their inventory history."
                )
            Toggle(
                "Stash It",
                stash,
                {
                    stash = it
                    if (it && allocationValues == listOf("") && output != null)
                        allocationValues =
                            listOf(
                                Volume.format(maxOf(0, output - existingAllocation), inputUnit, 5)
                            )
                },
            )
            if (stash) {
                Text(
                    "Allocate up to your measured output. These are new bags.",
                    style = MaterialTheme.typography.bodySmall,
                )
                allocationValues.forEachIndexed { i, v ->
                    Text("Bag allocation ${i+1}", color = MaterialTheme.colorScheme.secondary)
                    Field(
                        "Quantity (${inputUnit})",
                        v,
                        { n ->
                            allocationValues = allocationValues.toMutableList().also { it[i] = n }
                        },
                        true,
                    )
                    Choices(
                        if (s.deep) listOf("Fridge", "Freezer", "Deep Freezer")
                        else listOf("Fridge", "Freezer"),
                        allocationLocations[i],
                    ) { n ->
                        allocationLocations = allocationLocations.toMutableList().also { it[i] = n }
                    }
                    Text(
                        "Estimated expiry: ${dayOf(expiry(end,s.days(allocationLocations[i])))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row {
                    TextButton({
                        allocationValues = allocationValues + ""
                        allocationLocations = allocationLocations + "Fridge"
                    }) {
                        Text("+ Add bag")
                    }
                    if (allocationValues.size > 1)
                        TextButton({
                            allocationValues = allocationValues.dropLast(1)
                            allocationLocations = allocationLocations.dropLast(1)
                        }) {
                            Text("Remove last")
                        }
                }
            }
        }
        CardBlock {
            Toggle(
                "Next Pump",
                reminder,
                {
                    reminder = it
                    if (it) notification()
                },
            )
            if (reminder) {
                Timestamp("Remind at", remindAt, s) {
                    remindAt = it
                    customReminder = true
                }
                Row {
                    listOf(15, 30, 60).forEach { m ->
                        TextButton({
                            remindAt += m * 60000L
                            customReminder = true
                        }) {
                            Text("+$m min")
                        }
                    }
                }
            }
        }
        Field("Notes (optional)", notes, { notes = it }, lines = 3)
        if (existing != null)
            TextButton({ deleting = true }) {
                Text("Delete session", color = MaterialTheme.colorScheme.error)
            }
        Text(
            if (elapsedKnown) "Daily statistics use the session’s local start date."
            else "Daily grouping uses the saved Start time; actual total elapsed time is not recorded.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    pendingPhases?.let { next ->
        Confirm("Remove timing intervals?", "This removes interval information. Removing all intervals returns to active Duration and keeps your last edited time.",
            { pendingPhases = null; pendingPowerName = null }, {
                if (applyPhases(next)) {
                    pendingPowerName?.let { powerName = it }
                    pendingPhases = null; pendingPowerName = null
                }
            })
    }
    if (futureWarning)
        AlertDialog(
            onDismissRequest = { futureWarning = false },
            text = { Text("This session ends in the future. Did you mean to save it this way?") },
            dismissButton = { TextButton({ futureWarning = false }) { Text("Review") } },
            confirmButton = { TextButton({ futureWarning = false; saveSession() }) { Text("Save anyway") } },
        )
    if (discard)
        Confirm(
            "Discard changes?",
            "Your unsaved session changes will be lost.",
            { discard = false },
            close,
        )
    if (deleting && existing != null)
        DeleteRecordsDialog(vm, s, DeleteTarget.SESSION, existing.id, { deleting = false }, close)
}
