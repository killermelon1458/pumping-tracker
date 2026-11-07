package app.pumping.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pumping.*
import app.pumping.data.*
import app.pumping.domain.*
import java.time.*

@Composable
fun StashScreen(
    bags: List<BagBalance>,
    moves: List<Movement>,
    s: Settings,
    now: Long,
    add: () -> Unit,
    use: () -> Unit,
    detail: (String) -> Unit,
) {
    var period by rememberSaveable { mutableStateOf("Week") }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val today = dayOf(now)
    val from =
        if (period == "Week") today.minusDays(6).plusWeeks(offset.toLong())
        else today.withDayOfMonth(1).plusMonths(offset.toLong())
    val until = if (period == "Week") from.plusDays(7) else from.plusMonths(1)
    val ledger = moves.filter { it.time >= dayStart(from) && it.time < dayStart(until) }
    val added = ledger.filter { it.delta > 0 }.sumOf { it.delta }
    val used = -ledger.filter { it.delta < 0 }.sumOf { it.delta }
    val active = bags.filter { it.remaining > 0 }
    var showAll by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Heading("Stash") {
                TextButton(use) {
                    Text(
                        "−",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                TextButton(add) {
                    Text(
                        "+",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
        }
        item {
            Text("My Stash (${s.displayUnit})", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            CardBlock {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val locations =
                        listOf("Total", "Fridge", "Freezer") +
                            if (s.deep || active.any { it.bag.location == "Deep Freezer" })
                                listOf("Deep Freezer")
                            else emptyList()
                    locations.forEach { location ->
                        val selected =
                            if (location == "Total") active
                            else active.filter { it.bag.location == location }
                        Column(Modifier.weight(1f)) {
                            Text(location, style = MaterialTheme.typography.labelSmall)
                            Text(
                                Volume.format(selected.sumOf { it.remaining }, s.displayUnit),
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                "${selected.size} bags",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        if (active.isEmpty())
            item {
                CardBlock {
                    TextButton(add) {
                        Text(
                            if (bags.isEmpty()) "Tap here to add your first bag"
                            else "No milk remaining. Add a bag",
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        else {
            item { Text("Use soon", style = MaterialTheme.typography.titleLarge) }
            items(active, key = { it.bag.id }) { b ->
                CardBlock(Modifier.clickable { detail(b.bag.id) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(dayOf(b.bag.expires).toString())
                            Text(
                                if (b.bag.expires < now) "Past estimated expiry"
                                else "${(b.bag.expires-now)/86400000} days left",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text("#${b.bag.number}", color = MaterialTheme.colorScheme.secondary)
                        Text(quantity(b.remaining, s), fontWeight = FontWeight.Bold)
                    }
                    Text(b.bag.location, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (bags.isNotEmpty())
            item {
                TextButton({ showAll = !showAll }) {
                    Text(if (showAll) "Hide all bag records" else "Show all bag records")
                }
            }
        if (showAll)
            items(bags.sortedBy { it.bag.number }, key = { "record:${it.bag.id}" }) { b ->
                CardBlock(
                    Modifier.testTag("bag-record-${b.bag.id}").clickable { detail(b.bag.id) }
                ) {
                    Text("Bag #${b.bag.number}")
                    Text(
                        "${dateTime(b.bag.stored,s)} · ${quantity(b.remaining,s)} remaining · ${b.bag.location}"
                    )
                }
            }
        item {
            Choices(listOf("Week", "Month"), period) {
                period = it
                offset = 0
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ offset-- }) { Text("‹ Previous") }
                TextButton({ offset++ }, enabled = offset < 0) { Text("Next ›") }
            }
            Text("$from – ${until.minusDays(1)}")
        }
        item {
            CardBlock {
                Text("Added − Used = Result")
                Text(
                    "${quantity(added,s)} − ${quantity(used,s)} = ${quantity(added-used,s)}",
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        items(ledger, key = { it.id }) { m ->
            CardBlock(Modifier.clickable { detail(m.bagId) }) {
                val b = bags.find { it.bag.id == m.bagId }
                Text("${dateTime(m.time,s)} · #${b?.bag?.number ?: "?"}")
                Text(
                    "${m.kind}  ${if(m.delta>0) "+" else ""}${quantity(m.delta,s)}",
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (m.notes.isNotEmpty()) Text(m.notes)
            }
        }
        item {
            Text(
                "Storage dates are user-configured estimates, not a safety guarantee.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun BagEditor(vm: PumpViewModel, s: Settings, close: () -> Unit) {
    var inputUnit by rememberSaveable { mutableStateOf(s.inputUnit) }
    var amount by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("Fridge") }
    var time by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var notes by rememberSaveable { mutableStateOf("") }
    var number by remember { mutableIntStateOf(1) }
    var discard by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { number = vm.repo.dao.nextNumber() }
    BackHandler { discard = true }
    EditorLayout(
        "New Bag",
        { discard = true },
        {
            vm.launch {
                busy = true
                try {
                    vm.repo.addBag(
                        Allocation(
                            Volume.parse(amount, inputUnit),
                            location,
                            s.days(location),
                            notes,
                        ),
                        time,
                    )
                    vm.app.reminders.restore(vm.app)
                    close()
                } finally {
                    busy = false
                }
            }
        },
        !busy && (runCatching { Volume.parse(amount, inputUnit) }.getOrDefault(0) > 0),
        inputUnit = inputUnit,
        unitEnabled = !busy,
        onUnitToggle = {
            busy = true
            vm.launch {
                try {
                    val next = if (inputUnit == "oz") "mL" else "oz"
                    val converted = Volume.convertInput(amount, inputUnit, next)
                    vm.store.string("inputUnit", next)
                    amount = converted
                    inputUnit = next
                } finally {
                    busy = false
                }
            }
        },
    ) {
        CardBlock {
            Text("Bag Number", fontWeight = FontWeight.Bold)
            Text(
                "#$number",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                "Write this number on your bag. Assigned atomically when saved.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Timestamp("Date", time, s) { time = it }
        Field("Volume (${inputUnit})", amount, { amount = it }, true)
        CardBlock {
            Text("Location")
            Choices(
                if (s.deep) listOf("Fridge", "Freezer", "Deep Freezer")
                else listOf("Fridge", "Freezer"),
                location,
            ) {
                location = it
            }
            Text("Estimated expiry: ${dayOf(expiry(time,s.days(location)))}")
        }
        Field("Notes (optional)", notes, { notes = it }, lines = 3)
    }
    if (discard)
        Confirm(
            "Discard bag draft?",
            "No inventory has been added yet.",
            { discard = false },
            close,
        )
}

@Composable
fun UsageEditor(
    vm: PumpViewModel,
    s: Settings,
    bags: List<BagBalance>,
    initial: String?,
    close: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(initial ?: "") }
    var amount by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("Feeding") }
    var notes by rememberSaveable { mutableStateOf("") }
    var time by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var discard by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val bag = bags.find { it.bag.id == selected }
    LaunchedEffect(selected) { bag?.let { amount = Volume.format(it.remaining, s.inputUnit, 5) } }
    BackHandler { discard = true }
    EditorLayout(
        "Use Milk",
        { discard = true },
        {
            vm.launch {
                busy = true
                try {
                    vm.repo.useBag(selected, Volume.parse(amount, s.inputUnit), reason, time, notes)
                    vm.app.reminders.restore(vm.app)
                    close()
                } finally {
                    busy = false
                }
            }
        },
        !busy &&
            bag != null &&
            runCatching { Volume.parse(amount, s.inputUnit) }.getOrDefault(0) > 0,
    ) {
        CardBlock {
            Text("Bag Number")
            if (bags.none { it.remaining > 0 }) Text("No milk available. Add a bag first.")
            bags
                .filter { it.remaining > 0 }
                .forEach { b ->
                    FilterChip(
                        selected == b.bag.id,
                        { selected = b.bag.id },
                        label = {
                            Text(
                                "#${b.bag.number} · ${quantity(b.remaining,s)} · ${b.bag.location}"
                            )
                        },
                    )
                }
        }
        if (bag != null) {
            Field("Use quantity (${s.inputUnit})", amount, { amount = it }, true)
            Text("Partial withdrawals are supported. Remaining: ${quantity(bag.remaining,s)}.")
        }
        Choices(listOf("Feeding", "Donation", "Other", "Discard"), reason) { reason = it }
        Timestamp("Date", time, s) { time = it }
        Field("Notes (optional)", notes, { notes = it }, lines = 3)
    }
    if (discard)
        Confirm("Discard usage draft?", "No milk has been removed yet.", { discard = false }, close)
}

@Composable
fun BagDetail(
    vm: PumpViewModel,
    s: Settings,
    b: BagBalance?,
    moves: List<Movement>,
    close: () -> Unit,
    use: () -> Unit,
) {
    var location by rememberSaveable { mutableStateOf(b?.bag?.location ?: "Fridge") }
    var transfer by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(close) { Text("Close") }
        if (b == null) Text("Bag not found")
        else
            LazyColumn(
                modifier = Modifier.testTag("bag-detail-list"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Heading("Bag #${b.bag.number}")
                    Text("${quantity(b.remaining,s)} remaining · ${b.bag.location}")
                    Text("Stored: ${dateTime(b.bag.stored,s)}")
                    Text("Estimated expiry: ${dateTime(b.bag.expires,s)}")
                    if (b.bag.notes.isNotEmpty()) Text(b.bag.notes)
                }
                if (b.remaining > 0)
                    item {
                        Button(use) { Text("Use milk") }
                        Choices(
                            if (s.deep) listOf("Fridge", "Freezer", "Deep Freezer")
                            else listOf("Fridge", "Freezer"),
                            location,
                        ) {
                            location = it
                        }
                        TextButton({ transfer = true }, enabled = location != b.bag.location) {
                            Text("Move bag")
                        }
                        Text(
                            "Moving preserves the earlier expiry estimate.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                item {
                    TextButton({ deleting = true }) {
                        Text("Delete bag", color = MaterialTheme.colorScheme.error)
                    }
                }
                item { Text("Inventory history", style = MaterialTheme.typography.titleLarge) }
                items(moves.filter { it.bagId == b.bag.id }, key = { it.id }) { m ->
                    CardBlock {
                        Text(dateTime(m.time, s))
                        Text("${m.kind} · ${quantity(m.delta,s)}")
                        if (m.kind == "TRANSFER") Text("${m.oldLocation} → ${m.newLocation}")
                        if (m.notes.isNotEmpty()) Text(m.notes)
                    }
                }
            }
    }
    if (deleting && b != null)
        DeleteRecordsDialog(vm, s, DeleteTarget.BAG, b.bag.id, { deleting = false }, close)
    if (transfer && b != null)
        Confirm(
            "Move bag?",
            "Move to $location. This will not extend the existing expiry estimate.",
            { transfer = false },
            {
                vm.launch {
                    vm.repo.transfer(
                        b.bag.id,
                        location,
                        s.days(location),
                        System.currentTimeMillis(),
                    )
                    vm.app.reminders.restore(vm.app)
                    transfer = false
                }
            },
        )
}
