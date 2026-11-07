package app.pumping.reminders

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import app.pumping.*
import app.pumping.data.*
import app.pumping.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Reminders(private val context: Context) {
    companion object {
        const val LEGACY_CHANNEL = "pumping"
        const val PUMP_CHANNEL = "pump-reminders"
        const val OTHER_CHANNEL = "pumping-other"
        val PUMP_NOTIFICATION_ID = "pump".hashCode()
        const val SNOOZE = "pump-snooze-action"
        const val DISMISS = "pump-dismiss-action"
    }

    private val mutex = Mutex()
    private val scheduled = mutableMapOf<String, Triple<Long, String, Boolean>>()
    private val manager
        get() = context.getSystemService(NotificationManager::class.java)

    private val alarms
        get() = context.getSystemService(AlarmManager::class.java)

    /** An existing user's channel is never replaced or raised without explicit opt-in. */
    fun createChannels() {
        if (
            manager.getNotificationChannel(PUMP_CHANNEL) == null &&
                manager.getNotificationChannel(LEGACY_CHANNEL) == null
        )
            createDedicatedChannel()
        if (
            manager.getNotificationChannel(LEGACY_CHANNEL) == null &&
                manager.getNotificationChannel(OTHER_CHANNEL) == null
        )
            manager.createNotificationChannel(
                NotificationChannel(
                    OTHER_CHANNEL,
                    "Timer, parts and stash alerts",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
    }

    fun createDedicatedChannel() {
        if (manager.getNotificationChannel(PUMP_CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                    PUMP_CHANNEL,
                    "Pumping reminders",
                    NotificationManager.IMPORTANCE_HIGH,
                )
                .apply {
                    description = "Scheduled pumping reminders, snooze and dismissal"
                    enableVibration(true)
                    setSound(
                        AndroidSettings.System.DEFAULT_NOTIFICATION_URI,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
        )
    }

    fun pumpChannelId() =
        if (manager.getNotificationChannel(PUMP_CHANNEL) != null) PUMP_CHANNEL else LEGACY_CHANNEL

    fun usesLegacyChannel() = pumpChannelId() == LEGACY_CHANNEL

    fun channelSettingsIntent() =
        Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(AndroidSettings.EXTRA_CHANNEL_ID, pumpChannelId())

    fun exactSettingsIntent() =
        Intent(
            AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}"),
        )

    fun canScheduleExact() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun canNotify(): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) && manager.areNotificationsEnabled()

    private fun pending(
        kind: String,
        time: Long = 0,
        token: String = "",
        fallback: Boolean = false,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AlarmReceiver::class.java)
                .setAction(kind)
                .setData(
                    Uri.parse("pumping://alarm/$kind/${if (fallback) "fallback" else "primary"}")
                )
                .putExtra("due", time)
                .putExtra("token", token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** OS-owned PendingIntents work after ordinary process death, without an Activity/service. */
    fun schedule(kind: String, time: Long, token: String = "") {
        val exact = canScheduleExact()
        val signature = Triple(time, token, exact)
        synchronized(scheduled) {
            if (scheduled[kind] == signature) return
            alarms.cancel(pending(kind))
            alarms.cancel(pending(kind, fallback = true))
            scheduled[kind] = signature
            if (time <= 0) return
            val due = maxOf(time, System.currentTimeMillis() + 1000)
            if (exact) {
                try {
                    alarms.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        due,
                        pending(kind, time, token),
                    )
                } catch (_: SecurityException) {
                    // Special access can be revoked between checking and scheduling.
                }
            }
            // Keep an inexact safety alarm even with access: Android cancels exact alarms when
            // access is revoked. Durable tokens make the two delivery paths idempotent.
            alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                due,
                pending(kind, time, token, true),
            )
        }
    }

    private fun action(action: String, token: String) =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AlarmReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("pumping://notification/$action/$token"))
                .putExtra("token", token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun notify(kind: String, title: String, message: String, token: String = ""): Boolean {
        if (!canNotify()) return false
        val channel =
            if (kind == "pump") pumpChannelId()
            else if (manager.getNotificationChannel(LEGACY_CHANNEL) != null) LEGACY_CHANNEL
            else OTHER_CHANNEL
        if (
            manager.getNotificationChannel(channel)?.importance ==
                NotificationManager.IMPORTANCE_NONE
        )
            return false
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .setData(Uri.parse("pumping://notification/open/$kind/$token"))
                    .putExtra("pump-notification-token", if (kind == "pump") token else "")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val n =
            Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification_bottle)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setShowWhen(true)
        if (kind == "pump") {
            n.addAction(
                Notification.Action.Builder(null, "Snooze 5 min", action(SNOOZE, token)).build()
            )
            n.addAction(
                Notification.Action.Builder(null, "Dismiss", action(DISMISS, token)).build()
            )
            n.setDeleteIntent(action(DISMISS, token))
        }
        return try {
            manager.notify(kind.hashCode(), n.build())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun basis(s: app.pumping.data.Settings, sessions: List<Session>): String {
        val last = latestPump(sessions)
        return "${last?.id.orEmpty()}:${last?.start ?: 0}:${last?.end ?: 0}:${s.reminderMinutes}:${s.pumpReference.name}"
    }

    suspend fun setPumpReminder(app: PumpingApp, time: Long) =
        mutex.withLock {
            val s = app.settings.settings.first()
            val signature = basis(s, app.repository.dao.allSessions())
            app.settings.savePumpAlarm(
                PumpAlarmState(
                    enabled = time > 0,
                    due = time.coerceAtLeast(0),
                    token = if (time > 0) newId() else "",
                    basis = signature,
                )
            )
            manager.cancel(PUMP_NOTIFICATION_ID)
            armPump(app.settings.pumpAlarm.first())
        }

    suspend fun enableAutomatic(app: PumpingApp) {
        val s = app.settings.settings.first()
        val at =
            latestPump(app.repository.dao.allSessions())?.let {
                pumpReferenceTime(it, s.pumpReference)
            } ?: System.currentTimeMillis()
        setPumpReminder(app, nextAutomaticPump(at, s.reminderMinutes, System.currentTimeMillis()))
    }

    private fun armPump(state: PumpAlarmState) {
        schedule("pump", if (state.enabled) state.due else 0, state.token)
        schedule("pump-snooze", if (state.enabled) state.snoozeAt else 0, state.snoozeToken)
    }

    private suspend fun reconcilePump(app: PumpingApp, force: Boolean = false) {
        val s = app.settings.settings.first()
        val sessions = app.repository.dao.allSessions()
        val signature = basis(s, sessions)
        val before = app.settings.pumpAlarm.first()
        var state = before
        if (state.enabled && (force || (state.basis.isNotEmpty() && state.basis != signature))) {
            val at = latestPump(sessions)?.let { pumpReferenceTime(it, s.pumpReference) }
            state =
                state.copy(
                    due =
                        at?.let {
                            nextAutomaticPump(it, s.reminderMinutes, System.currentTimeMillis())
                        } ?: 0,
                    token = newId(),
                    snoozeAt = 0,
                    snoozeToken = "",
                    visibleToken = "",
                )
            manager.cancel(PUMP_NOTIFICATION_ID)
        }
        if (
            state.enabled &&
                state.due > 0 &&
                System.currentTimeMillis() - state.due > s.reminderMinutes * 60000L
        ) {
            state =
                state.copy(
                    due =
                        nextAutomaticPump(state.due, s.reminderMinutes, System.currentTimeMillis()),
                    token = newId(),
                )
        }
        if (state.due > 0 && state.token.isEmpty()) {
            manager.cancel(
                PUMP_NOTIFICATION_ID
            ) // Remove any pre-generation notification on upgrade.
            state = state.copy(token = newId())
        }
        state = state.copy(basis = signature)
        if (state != before) app.settings.savePumpAlarm(state)
        armPump(state)
    }

    suspend fun changeOptions(
        app: PumpingApp,
        minutes: Int,
        reference: PumpReference,
        morning: Int,
    ) =
        mutex.withLock {
            val old = app.settings.settings.first()
            app.settings.reminderOptions(minutes, reference, morning)
            reconcilePump(
                app,
                force = old.reminderMinutes != minutes || old.pumpReference != reference,
            )
        }

    suspend fun restore(app: PumpingApp) =
        mutex.withLock {
            reconcilePump(app)
            restoreOther(app)
        }

    private suspend fun restoreOther(app: PumpingApp) {
        val s = app.settings.settings.first()
        schedule("parts", s.partsAt)
        val t = app.settings.timer.first()
        val boot =
            AndroidSettings.Global.getInt(
                context.contentResolver,
                AndroidSettings.Global.BOOT_COUNT,
                0,
            )
        if (t.running && t.boot != boot) {
            app.settings.saveTimer(t.copy(running = false, tick = 0))
            schedule("timer", 0)
        } else if (t.running) {
            val identity = "${t.id}:${t.targetMinutes}"
            val handled = app.settings.deliveryHandled("timer", identity)
            schedule(
                "timer",
                if (handled) 0
                else
                    System.currentTimeMillis() +
                        maxOf(
                            1000,
                            t.targetMinutes * 60000L -
                                t.elapsed(android.os.SystemClock.elapsedRealtime(), boot),
                        ),
            )
        } else schedule("timer", 0)
        val nearest =
            if (s.expiryAlerts)
                app.repository.dao
                    .allBags()
                    .filter { app.repository.dao.remaining(it.id) > 0 }
                    .minOfOrNull { it.expires }
            else null
        schedule(
            "expiry",
            nearest?.let {
                maxOf(
                    System.currentTimeMillis() + 60000,
                    it - 86400000,
                    s.lastExpiryAlert + 86400000,
                )
            } ?: 0,
        )
    }

    /** All receiver actions are serialized, including simultaneous exact/fallback delivery. */
    suspend fun receive(app: PumpingApp, intent: Intent, now: Long = System.currentTimeMillis()) =
        mutex.withLock {
            val kind = intent.action ?: return@withLock
            if (kind in listOf("pump", "pump-snooze", SNOOZE, DISMISS)) {
                // A killed process can start directly here; read timing and permissions at
                // delivery.
                reconcilePump(app)
                val state = app.settings.pumpAlarm.first()
                val token = intent.getStringExtra("token").orEmpty()
                if (kind == SNOOZE || kind == DISMISS) {
                    if (token.isEmpty() || token != state.visibleToken) return@withLock
                    val next =
                        if (kind == SNOOZE && state.enabled)
                            state.copy(
                                visibleToken = "",
                                snoozeAt = now + 300000,
                                snoozeToken = newId(),
                            )
                        else state.copy(visibleToken = "")
                    app.settings.savePumpAlarm(next)
                    manager.cancel(PUMP_NOTIFICATION_ID)
                    armPump(next)
                    return@withLock
                }
                val snooze = kind == "pump-snooze"
                val expected = if (snooze) state.snoozeToken else state.token
                val due = if (snooze) state.snoozeAt else state.due
                if (
                    !state.enabled ||
                        token.isEmpty() ||
                        token != expected ||
                        due <= 0 ||
                        intent.getLongExtra("due", -1) != due ||
                        now + 1000 < due
                )
                    return@withLock
                val s = app.settings.settings.first()
                val next =
                    if (snooze) state.copy(snoozeAt = 0, snoozeToken = "", visibleToken = token)
                    else
                        state.copy(
                            due = nextAutomaticPump(due, s.reminderMinutes, now),
                            token = newId(),
                            visibleToken = token,
                        )
                // Persist consumption before posting, so replaying an alarm cannot alert twice.
                app.settings.savePumpAlarm(next)
                armPump(next)
                val body =
                    pumpNotificationText(app.repository.dao.allSessions(), s.pumpReference, now)
                if (!notify("pump", "It's time to pump", body, token))
                    app.settings.savePumpAlarm(next.copy(visibleToken = ""))
                return@withLock
            }
            val s = app.settings.settings.first()
            val due = intent.getLongExtra("due", 0)
            if (due <= 0 || now + 1000 < due) return@withLock
            when (kind) {
                "timer" -> {
                    val t = app.settings.timer.first()
                    val boot =
                        AndroidSettings.Global.getInt(
                            context.contentResolver,
                            AndroidSettings.Global.BOOT_COUNT,
                            0,
                        )
                    if (
                        t.running &&
                            t.boot == boot &&
                            t.elapsed(android.os.SystemClock.elapsedRealtime(), boot) >=
                                t.targetMinutes * 60000L &&
                            app.settings.claimDelivery("timer", "${t.id}:${t.targetMinutes}")
                    ) {
                        schedule("timer", 0)
                        notify(
                            "timer",
                            "Timer target reached",
                            "Your timer will keep counting until you pause or finish.",
                        )
                    }
                }
                "parts" ->
                    if (s.partsAt == due && app.settings.claimDelivery("parts", due.toString())) {
                        app.settings.long("partsAt", 0)
                        schedule("parts", 0)
                        notify(
                            "parts",
                            "Pump parts reminder",
                            "Review your pump parts using your chosen replacement schedule.",
                        )
                    }
                "expiry" -> {
                    val hasMilk =
                        s.expiryAlerts &&
                            now - s.lastExpiryAlert >= 86400000 &&
                            app.repository.dao.allBags().any {
                                app.repository.dao.remaining(it.id) > 0 &&
                                    it.expires <= now + 86400000
                            }
                    if (hasMilk) {
                        app.settings.long("lastExpiryAlert", now)
                        notify(
                            "expiry",
                            "Review your stored milk",
                            "A storage estimate is approaching. Check your stash.",
                        )
                    }
                    restoreOther(app)
                }
            }
        }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as PumpingApp
                app.reminders.receive(app, intent)
            } finally {
                result.finish()
            }
        }
    }
}

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action !in
                setOf(
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                )
        )
            return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as PumpingApp
                app.reminders.restore(app)
            } finally {
                result.finish()
            }
        }
    }
}
