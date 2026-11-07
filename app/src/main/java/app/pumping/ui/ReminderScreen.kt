package app.pumping.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import app.pumping.*
import app.pumping.data.*
import app.pumping.domain.*

@Composable
fun ReminderScreen(
    vm: PumpViewModel,
    s: Settings,
    now: Long,
    notification: () -> Unit,
    close: () -> Unit,
    settings: () -> Unit,
) {
    var time by rememberSaveable {
        mutableLongStateOf(
            s.nextPump.takeIf { it > 0 }
                ?: latestPump(vm.sessions.value)
                    ?.let { pumpReferenceTime(it, s.pumpReference) }
                    ?.let { nextAutomaticPump(it, s.reminderMinutes, now) }
                ?: nextReminder(now, s.reminderMinutes)
        )
    }
    EditorLayout(
        "Next Pump",
        close,
        {
            notification()
            vm.launch {
                vm.reminder(time)
                close()
            }
        },
    ) {
        CardBlock {
            Text(
                if (time > now) "In ${gap(time-now)}" else "Reminder due",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Timestamp("Remind at", time, s) { time = it }
            Row {
                listOf(15, 30, 60).forEach { m ->
                    TextButton({ time += m * 60000L }) { Text("+$m min") }
                }
            }
        }
        CardBlock {
            Text("Skip This Time")
            Text("Move forward by your ${s.reminderMinutes}-minute interval.")
            Button({ time = nextReminder(maxOf(time, now), s.reminderMinutes) }) {
                Text("Skip once")
            }
        }
        CardBlock {
            Text("Skip Tonight")
            Text("Tomorrow at ${s.morningHour.toString().padStart(2,'0')}:00")
            Button({ time = nextMorning(now, s.morningHour) }) { Text("Skip tonight") }
        }
        TextButton(settings) { Text("Reminder Settings") }
        if (s.nextPump > 0)
            TextButton({
                vm.launch {
                    vm.reminder(0)
                    close()
                }
            }) {
                Text("Turn reminders off")
            }
        ReminderDeliverySettings(vm, notification)
    }
}
