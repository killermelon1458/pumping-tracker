package app.pumping.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pumping.*
import app.pumping.data.*
import app.pumping.domain.*
import java.time.LocalDate

@Composable
fun ProfileScreen(
    vm: PumpViewModel,
    s: Settings,
    notification: () -> Unit,
    open: (String) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Heading("Profile") }
        item {
            CardBlock {
                Text(
                    s.child.ifBlank { "Your pumping journey" },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    if (s.goalEnd.isBlank()) "Your records belong to you." else "Goal: ${s.goalEnd}"
                )
                TextButton({ open("goal") }) { Text("Edit journey") }
            }
        }
        item {
            CardBlock {
                Text("Input Units")
                Column(Modifier.testTag("input-units")) {
                    Choices(listOf("oz", "mL"), s.inputUnit) {
                        vm.launch { vm.store.string("inputUnit", it) }
                    }
                }
                Text("Display Units")
                Column(Modifier.testTag("display-units")) {
                    Choices(listOf("oz", "mL"), s.displayUnit) {
                        vm.launch { vm.store.string("displayUnit", it) }
                    }
                }
                Text("Time Format")
                Choices(listOf("12h", "24h"), if (s.clock24) "24h" else "12h") {
                    vm.launch { vm.store.bool("24h", it == "24h") }
                }
                TextButton({ open("reminderSettings") }) {
                    Text("Reminders · every ${s.reminderMinutes} min")
                }
                TextButton({ open("parts") }) { Text("Pump Parts Reminder") }
                TextButton({ open("storage") }) { Text("Storage Duration Settings") }
                Toggle(
                    "Deep Freezer",
                    s.deep,
                    { vm.launch { vm.store.bool("deep", it) } },
                    "Enable long-term storage location",
                )
                Toggle(
                    "Time between pumps",
                    s.intervals,
                    { vm.launch { vm.store.bool("intervals", it) } },
                )
                Toggle("Dark Mode", s.dark, { vm.launch { vm.store.bool("dark", it) } })
            }
        }
        item { BackupControls(vm) }
        item {
            CardBlock {
                TextButton({ open("savings") }) { Text("Savings assumptions") }
                TextButton({ open("privacy") }) { Text("Privacy & About") }
                TextButton({
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Pumping — a local-first Android pumping and milk inventory tracker.",
                                ),
                            "Share Pumping",
                        )
                    )
                }) {
                    Text("Share with Friends")
                }
            }
        }
        item {
            Text(
                "Pumping 1.4.0 · Offline, with no account or subscription",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun GoalEditor(vm: PumpViewModel, s: Settings, close: () -> Unit) {
    var child by rememberSaveable { mutableStateOf(s.child) }
    var start by rememberSaveable {
        mutableStateOf(s.goalStart.ifBlank { LocalDate.now().toString() })
    }
    var end by rememberSaveable { mutableStateOf(s.goalEnd) }
    EditorLayout(
        "Journey & Goal",
        close,
        {
            vm.launch {
                val a = LocalDate.parse(start)
                val b = LocalDate.parse(end)
                require(b > a) { "Goal end must follow start" }
                vm.store.string("child", child)
                vm.store.string("goalStart", a.toString())
                vm.store.string("goalEnd", b.toString())
                close()
            }
        },
    ) {
        Field("Journey name (optional)", child, { child = it })
        Field("Start date (YYYY-MM-DD)", start, { start = it })
        Field("Goal date (YYYY-MM-DD)", end, { end = it })
        Text(
            "A name is optional. Store only information you are comfortable keeping on this device."
        )
    }
}

@Composable
fun SettingsDetail(
    vm: PumpViewModel,
    s: Settings,
    page: String,
    notification: () -> Unit,
    close: () -> Unit,
) {
    var interval by rememberSaveable { mutableStateOf(s.reminderMinutes.toString()) }
    var reference by rememberSaveable { mutableStateOf(s.pumpReference.name) }
    var morning by rememberSaveable { mutableStateOf(s.morningHour.toString()) }
    var fridge by rememberSaveable { mutableStateOf(s.fridgeDays.toString()) }
    var freezer by rememberSaveable { mutableStateOf(s.freezerDays.toString()) }
    var deep by rememberSaveable { mutableStateOf(s.deepDays.toString()) }
    var parts by rememberSaveable { mutableStateOf(s.partsDays.toString()) }
    var savings by rememberSaveable { mutableStateOf(s.savingsPerLiter) }
    var alerts by rememberSaveable { mutableStateOf(s.expiryAlerts) }
    val title =
        when (page) {
            "reminderSettings" -> "Reminder Settings"
            "storage" -> "Storage Duration"
            "parts" -> "Pump Parts Reminder"
            "savings" -> "Savings Assumptions"
            else -> "Privacy & About"
        }
    EditorLayout(
        title,
        close,
        {
            vm.launch {
                fun positive(text: String, max: Int): Int {
                    val n = text.toIntOrNull() ?: error("Enter a whole number")
                    require(n in 1..max) { "Value must be between 1 and $max" }
                    return n
                }
                when (page) {
                    "reminderSettings" -> {
                        val mins = positive(interval, 10080)
                        val hour = morning.toIntOrNull() ?: error("Enter an hour")
                        require(hour in 0..23) { "Hour must be 0–23" }
                        vm.app.reminders.changeOptions(
                            vm.app,
                            mins,
                            PumpReference.from(reference),
                            hour,
                        )
                    }
                    "storage" -> {
                        val a = positive(fridge, 3650)
                        val b = positive(freezer, 3650)
                        val c = positive(deep, 3650)
                        vm.store.int("fridge", a)
                        vm.store.int("freezer", b)
                        vm.store.int("deepDays", c)
                        vm.store.bool("expiryAlerts", alerts)
                        if (alerts) notification()
                        vm.app.reminders.restore(vm.app)
                    }
                    "parts" -> {
                        val d = positive(parts, 3650)
                        vm.store.int("partsDays", d)
                        vm.store.long("partsAt", System.currentTimeMillis() + d * 86400000L)
                        notification()
                        vm.app.reminders.schedule(
                            "parts",
                            System.currentTimeMillis() + d * 86400000L,
                        )
                    }
                    "savings" -> {
                        val rate = savings.toBigDecimalOrNull() ?: error("Enter a valid cost")
                        require(
                            rate >= java.math.BigDecimal.ZERO &&
                                rate <= java.math.BigDecimal("1000000")
                        )
                        vm.store.string("savings", rate.toPlainString())
                    }
                }
                close()
            }
        },
    ) {
        when (page) {
            "reminderSettings" -> {
                Field("Default interval (minutes)", interval, { interval = it }, true)
                Field("Morning resume hour (0–23)", morning, { morning = it }, true)
                Text("Measure time between pumps from:")
                Choices(
                    PumpReference.entries.map { it.label },
                    PumpReference.from(reference).label,
                ) { label ->
                    reference = PumpReference.entries.first { it.label == label }.name
                }
                Text(
                    "Start is the default. Saving a changed reference or interval recalculates enabled automatic reminders. Session timestamps stay unchanged."
                )
                ReminderDeliverySettings(vm, notification)
                val alarm by vm.store.pumpAlarm.collectAsState(initial = PumpAlarmState())
                Toggle(
                    "Pump reminders",
                    alarm.enabled,
                    { enabled ->
                        if (enabled) notification()
                        vm.launch {
                            if (enabled) vm.app.reminders.enableAutomatic(vm.app)
                            else vm.reminder(0)
                        }
                    },
                )
                if (alarm.enabled && alarm.due == 0L) Text("Waiting for a pump with valid timing.")
            }
            "storage" -> {
                Field("Fridge (days)", fridge, { fridge = it }, true)
                Field("Freezer (days)", freezer, { freezer = it }, true)
                Field("Deep Freezer (days)", deep, { deep = it }, true)
                Toggle("Expiry alerts", alerts, { alerts = it })
                Text(
                    "Durations apply to new bags. Existing bags retain their saved estimates. These are estimates, not medical or food-safety guarantees."
                )
            }
            "parts" -> {
                Field("Remind me in (days)", parts, { parts = it }, true)
                Text(
                    "Choose your own interval based on your pump manufacturer’s instructions. Save schedules a one-time reminder."
                )
                if (s.partsAt > 0) {
                    Text("Scheduled: ${dateTime(s.partsAt,s)}")
                    TextButton({
                        vm.launch {
                            vm.store.long("partsAt", 0)
                            vm.app.reminders.schedule("parts", 0)
                            close()
                        }
                    }) {
                        Text("Cancel parts reminder")
                    }
                }
            }
            "savings" -> {
                Field("Estimated formula cost per liter", savings, { savings = it }, true)
                Text(
                    "Use your own local currency consistently. Savings use only inventory withdrawals marked Feeding, excluding donation, other use and discards."
                )
            }
            else -> {
                Text(
                    "Pumping stores your sessions, milk inventory and settings locally. No account, advertisements, telemetry, or network permission is used. Android system backups are disabled for these sensitive records."
                )
                Text(
                    "You control backup files. Exported JSON and CSV files contain private records and are not encrypted by Pumping. Store them securely."
                )
                Text(
                    "Uninstalling removes local data. Export a backup before switching devices. Reminders and storage estimates are organizational aids."
                )
                Text(
                    "Independent implementation with original artwork. No affiliation with the screenshot reference application."
                )
            }
        }
    }
}
