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
    var duration by rememberSaveable { mutableStateOf((initialSeconds / 60.0).toString()) }
    var committedSeconds by rememberSaveable { mutableLongStateOf(initialSeconds) }
    val linkedTiming = timer == null && existing?.mode != "TIMER"
    var historyNames by rememberSaveable { mutableStateOf(listOf("END", "DURATION")) }
    var durationDirty by rememberSaveable { mutableStateOf(false) }
    var timingError by rememberSaveable { mutableStateOf<String?>(null) }
    var futureWarning by rememberSaveable { mutableStateOf(false) }
    fun commitTime(field: TimeField, value: Long): Boolean {
        return try {
            if (linkedTiming) {
                val next = QuickLogTiming(start, end, committedSeconds,
                    historyNames.map(TimeField::valueOf)).edit(field, value)
                committedSeconds = next.seconds
                start = next.start
                end = next.end
                duration = (next.seconds / 60.0).toString()
                historyNames = next.history.map { it.name }
            } else {
                // Timed sessions retain active time independently of their paused wall-clock span.
                when (field) {
                    TimeField.START -> start = value
                    TimeField.END -> end = value
                    TimeField.DURATION -> { duration = (value / 60.0).toString(); end = start + value * 1000 }
                }
            }
            if (!linkedTiming && field == TimeField.DURATION) committedSeconds = value
            durationDirty = false
            timingError = null
            true
        } catch (e: Exception) {
            timingError = e.message ?: "Invalid session interval"
            false
        }
    }
    fun commitDuration(): Boolean {
        if (!durationDirty) return true
        return try { commitTime(TimeField.DURATION, durationSeconds(duration)) }
        catch (e: Exception) { timingError = "Enter a valid positive duration"; false }
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
    var allocationValues by rememberSaveable { mutableStateOf(listOf("")) }
    var allocationLocations by rememberSaveable { mutableStateOf(listOf("Fridge")) }
    var discard by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var existingAllocation by remember { mutableLongStateOf(0) }
    LaunchedEffect(existing?.id) {
        existing?.let { existingAllocation = vm.repo.dao.allocated(it.id) }
    }
    BackHandler { discard = true }
    fun measured(): Long =
        if (zero) 0
        else if (split)
            Math.addExact(
                Volume.parse(left.ifBlank { "0" }, inputUnit),
                Volume.parse(right.ifBlank { "0" }, inputUnit),
            )
        else Volume.parse(total, inputUnit)
    val output = runCatching { measured() }.getOrNull()
    fun saveSession() {
        vm.launch {
            busy = true
            try {
                val amount = measured()
                require(amount > 0 || zero) { "Enter output or select No milk expressed" }
                val seconds = committedSeconds
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
                vm.save(session, allocations, if (reminder) remindAt else null)
                vm.app.reminders.restore(vm.app)
                close()
            } finally {
                busy = false
            }
        }
    }
    fun requestSave() {
        if (!commitDuration()) return
        if (end <= start || committedSeconds <= 0 || committedSeconds > (end - start) / 1000) {
            timingError = "Active duration must fit inside start and end times"
            return
        }
        if (!customReminder) remindAt = nextReminder(referenceAt(), s.reminderMinutes)
        if (end > System.currentTimeMillis()) futureWarning = true else saveSession()
    }
    EditorLayout(
        if (existing == null) "Log Pump" else "Edit Pump",
        { discard = true },
        { requestSave() },
        !busy && pumpInitialized && output != null && (output > 0 || zero),
        inputUnit = inputUnit,
        unitEnabled = !busy,
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
        CardBlock {
            Field(
                "Active duration (minutes)",
                duration,
                { v -> duration = v; durationDirty = true; timingError = null },
                true,
                onCommit = {
                    if (commitDuration() && !customReminder)
                        remindAt = nextReminder(referenceAt(), s.reminderMinutes)
                },
            )
            Timestamp("Started", start, s) {
                if (commitDuration() && commitTime(TimeField.START, it) && !customReminder)
                    remindAt = nextReminder(referenceAt(), s.reminderMinutes)
            }
            Timestamp("Ended", end, s) {
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
            "Daily statistics use the session’s local start date.",
            style = MaterialTheme.typography.bodySmall,
        )
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
