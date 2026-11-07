package app.pumping.ui

import android.content.ActivityNotFoundException
import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.pumping.PumpViewModel

@Composable
fun ReminderDeliverySettings(vm: PumpViewModel, permission: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var migrate by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val exact = remember(refresh) { vm.app.reminders.canScheduleExact() }
    val allowed = remember(refresh) { vm.app.reminders.canNotify() }
    val legacy = remember(refresh) { vm.app.reminders.usesLegacyChannel() }
    Text(
        if (exact)
            "Precise alarms are available. Android battery and Do Not Disturb rules still apply."
        else
            "Approximate timing: precise alarm access is off. Android can delay reminders, especially while idle. Snooze targets five minutes but may arrive later."
    )
    if (Build.VERSION.SDK_INT >= 31 && !exact)
        TextButton({
            try {
                context.startActivity(vm.app.reminders.exactSettingsIntent())
            } catch (_: ActivityNotFoundException) {
                vm.error.value = "This device does not provide the precise-alarm settings screen."
            }
        }) {
            Text("Allow precise reminders")
        }
    if (!allowed)
        Text(
            "Notifications are blocked. Enable permission and alerts in Android settings to receive reminders."
        )
    TextButton(permission) { Text("Enable notification permission") }
    TextButton({
        try {
            context.startActivity(vm.app.reminders.channelSettingsIntent())
        } catch (_: ActivityNotFoundException) {
            vm.error.value = "Notification-channel settings are unavailable on this device."
        }
    }) {
        Text("Notification sound, vibration & pop-up settings")
    }
    if (legacy) {
        Text(
            "Using your existing notification channel. Its current sound and importance are preserved."
        )
        TextButton({ migrate = true }) { Text("Set up dedicated reminder alerts") }
    }
    Text(
        "Alerts respect your channel settings, silent mode and Do Not Disturb. After force-stopping Pumping in Android Settings, reopen it to restore alarms. Some manufacturers also restrict background apps."
    )
    if (migrate)
        Confirm(
            "Use dedicated reminder alerts?",
            "Create a separate pumping-reminder channel with sound, vibration and high importance. Your old channel stays unchanged. Android will let you customize or block the new channel.",
            { migrate = false },
            {
                vm.app.reminders.createDedicatedChannel()
                refresh++
                migrate = false
                context.startActivity(vm.app.reminders.channelSettingsIntent())
            },
        )
}
