package app.pumping

import android.Manifest
import android.app.*
import android.content.Intent
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pumping.data.*
import app.pumping.domain.*
import app.pumping.reminders.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

open class ReminderTestApplication : PumpingApp() {
    val job = SupervisorJob()
    override val db by lazy {
        Room.inMemoryDatabaseBuilder(this, PumpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }
    val preferenceFile by lazy { File(cacheDir, "alarm-${UUID.randomUUID()}.preferences_pb") }
    val testStore by lazy {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO),
            produceFile = { preferenceFile },
        )
    }
    override val settings by lazy { SettingsStore(this, db.dao(), testStore) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 31, 33, 35], application = ReminderTestApplication::class)
class ReminderReliabilityTest {
    private lateinit var app: ReminderTestApplication
    private val manager
        get() = app.getSystemService(NotificationManager::class.java)

    private val alarms
        get() = shadowOf(app.getSystemService(AlarmManager::class.java))

    private val now
        get() = System.currentTimeMillis()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        if (Build.VERSION.SDK_INT >= 33)
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun close() = runBlocking {
        app.job.cancelAndJoin()
        app.db.close()
        app.preferenceFile.delete()
        Unit
    }

    private suspend fun seed(): Session {
        val s =
            Session(
                start = now - 200 * 60000L,
                end = now - 180 * 60000L,
                activeSeconds = 1200,
                volume = 1000,
            )
        app.repository.saveSession(s)
        return s
    }

    private fun alarmIntent(state: PumpAlarmState, snooze: Boolean = false) =
        Intent(if (snooze) "pump-snooze" else "pump")
            .putExtra("token", if (snooze) state.snoozeToken else state.token)
            .putExtra("due", if (snooze) state.snoozeAt else state.due)

    private fun notification() = shadowOf(manager).allNotifications.single()

    private fun pumpAlarms() =
        alarms.scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == "pump" }

    @Test
    fun schedulesCancellationAndFreshSchedulerRecovery() = runBlocking {
        app.reminders.setPumpReminder(app, now + 60000)
        repeat(3) { app.reminders.restore(app) }
        assertEquals(
            1,
            pumpAlarms().count {
                shadowOf(it.operation).savedIntent.data!!.lastPathSegment == "fallback"
            },
        )
        assertTrue(
            pumpAlarms()
                .groupBy { shadowOf(it.operation).savedIntent.data }
                .values
                .all { it.size == 1 }
        )
        val saved = app.settings.pumpAlarm.first()
        Reminders(app).restore(app)
        assertEquals(saved, app.settings.pumpAlarm.first())
        app.reminders.setPumpReminder(app, 0)
        assertTrue(pumpAlarms().isEmpty())
        assertFalse(app.settings.pumpAlarm.first().enabled)
    }

    @Test
    fun unavailableExactAccessKeepsIdleFallbackAndGrantAddsExactPath() = runBlocking {
        if (Build.VERSION.SDK_INT < 31) return@runBlocking
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        app.reminders.setPumpReminder(app, now + 60000)
        assertFalse(app.reminders.canScheduleExact())
        assertEquals(1, pumpAlarms().size)
        assertEquals(
            "fallback",
            shadowOf(pumpAlarms().single().operation).savedIntent.data!!.lastPathSegment,
        )
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        app.reminders.restore(app)
        assertEquals(2, pumpAlarms().size)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        app.reminders.restore(app)
        assertEquals(1, pumpAlarms().size)
    }

    @Test
    fun deliveredTextAndExpandedActionsUseActualSelectedTimestamp() = runBlocking {
        seed()
        val at = now
        app.reminders.setPumpReminder(app, at)
        app.reminders.receive(app, alarmIntent(app.settings.pumpAlarm.first()), at)
        val n = notification()
        assertEquals(
            "It's time to pump",
            n.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertEquals(
            "Last pump started 3h 20m ago",
            n.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertEquals(listOf("Snooze 5 min", "Dismiss"), n.actions.map { it.title.toString() })
        assertEquals(R.drawable.ic_notification_bottle, n.smallIcon.resId)
        assertEquals(0, n.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(0L, n.timeoutAfter)
    }

    @Test
    fun duplicateAndObsoleteAlarmsCannotPostOrAdvanceTwice() = runBlocking {
        val at = now
        app.reminders.setPumpReminder(app, at)
        val intent = alarmIntent(app.settings.pumpAlarm.first())
        app.reminders.receive(app, intent, at)
        val consumed = app.settings.pumpAlarm.first()
        manager.cancelAll()
        repeat(3) { app.reminders.receive(app, intent, at) }
        assertEquals(consumed, app.settings.pumpAlarm.first())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        app.reminders.setPumpReminder(app, at + 60000)
        app.reminders.receive(app, intent, at + 60000)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun snoozeDismissAndStaleActionsPreserveFutureRemindersAndRecords() = runBlocking {
        val session = seed()
        val at = now
        app.reminders.setPumpReminder(app, at)
        app.reminders.receive(app, alarmIntent(app.settings.pumpAlarm.first()), at)
        val visible = app.settings.pumpAlarm.first()
        app.reminders.receive(
            app,
            Intent(Reminders.SNOOZE).putExtra("token", visible.visibleToken),
            at,
        )
        val snoozed = app.settings.pumpAlarm.first()
        assertEquals(at + 300000, snoozed.snoozeAt)
        assertEquals(visible.due, snoozed.due)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        app.reminders.receive(
            app,
            Intent(Reminders.SNOOZE).putExtra("token", visible.visibleToken),
            at + 1,
        )
        assertEquals(snoozed, app.settings.pumpAlarm.first())
        app.reminders.receive(app, alarmIntent(snoozed, true), snoozed.snoozeAt)
        val delivered = app.settings.pumpAlarm.first()
        assertEquals(0L, delivered.snoozeAt)
        app.reminders.receive(
            app,
            Intent(Reminders.DISMISS).putExtra("token", delivered.visibleToken),
            snoozed.snoozeAt,
        )
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertTrue(app.settings.pumpAlarm.first().enabled)
        assertEquals(visible.due, app.settings.pumpAlarm.first().due)
        assertEquals(session, app.repository.dao.session(session.id))
        assertEquals(PumpReference.START, app.settings.settings.first().pumpReference)
    }

    @Test
    fun settingsAndSessionChangesReplaceAutomaticAlarmWithoutChangingRecords() = runBlocking {
        val s = seed()
        app.reminders.enableAutomatic(app)
        app.reminders.changeOptions(app, 240, PumpReference.END, 7)
        assertEquals(s.end + 240 * 60000L, app.settings.pumpAlarm.first().due)
        val endToken = app.settings.pumpAlarm.first().token
        app.reminders.changeOptions(app, 240, PumpReference.START, 7)
        assertEquals(s.start + 240 * 60000L, app.settings.pumpAlarm.first().due)
        assertNotEquals(endToken, app.settings.pumpAlarm.first().token)
        assertEquals(s, app.repository.dao.session(s.id))
        val changed = s.copy(start = s.start + 60000, end = s.end + 60000)
        app.repository.saveSession(changed)
        app.reminders.restore(app)
        assertEquals(changed.start + 240 * 60000L, app.settings.pumpAlarm.first().due)
        app.repository.deleteSession(changed.id)
        app.reminders.restore(app)
        assertEquals(0L, app.settings.pumpAlarm.first().due)
        assertTrue(pumpAlarms().isEmpty())
    }

    @Test
    fun rebootRestoresPersistedSnoozeAndMainDeadline() = runBlocking {
        val at = now
        app.reminders.setPumpReminder(app, at)
        app.reminders.receive(app, alarmIntent(app.settings.pumpAlarm.first()), at)
        val token = app.settings.pumpAlarm.first().visibleToken
        app.reminders.receive(app, Intent(Reminders.SNOOZE).putExtra("token", token), at)
        val saved = app.settings.pumpAlarm.first()
        alarms.scheduledAlarms.toList().forEach {
            app.getSystemService(AlarmManager::class.java).cancel(requireNotNull(it.operation))
        }
        Reminders(app).restore(app)
        assertEquals(saved, app.settings.pumpAlarm.first())
        assertTrue(
            alarms.scheduledAlarms.any {
                shadowOf(it.operation).savedIntent.action == "pump-snooze"
            }
        )
        assertTrue(pumpAlarms().isNotEmpty())
    }

    @Test
    fun deniedNotificationsConsumeOnlyCurrentDeliveryAndKeepSchedule() = runBlocking {
        if (Build.VERSION.SDK_INT < 33) return@runBlocking
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val at = now
        app.reminders.setPumpReminder(app, at)
        val original = app.settings.pumpAlarm.first()
        app.reminders.receive(app, alarmIntent(original), at)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        val state = app.settings.pumpAlarm.first()
        assertNotEquals(original.token, state.token)
        assertTrue(state.enabled && state.due > at)
        assertEquals("", state.visibleToken)
    }

    @Test
    fun newChannelIsHighAndExistingChannelChoicesAreNotReset() {
        val channel = manager.getNotificationChannel(Reminders.PUMP_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertTrue(channel.shouldVibrate())
        assertNotNull(channel.sound)
        manager.deleteNotificationChannel(Reminders.PUMP_CHANNEL)
        manager.createNotificationChannel(
            NotificationChannel(Reminders.LEGACY_CHANNEL, "Old", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    setSound(null, null)
                    enableVibration(false)
                }
        )
        app.reminders.createChannels()
        // Android retains deleted channel preferences; testing legacy selection independently uses
        // the manager's channel registry after deleting the fresh channel.
        if (manager.getNotificationChannel(Reminders.PUMP_CHANNEL) == null)
            assertEquals(Reminders.LEGACY_CHANNEL, app.reminders.pumpChannelId())
        val old = manager.getNotificationChannel(Reminders.LEGACY_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, old.importance)
        assertNull(old.sound)
        assertFalse(old.shouldVibrate())
        assertEquals(
            app.packageName,
            app.reminders
                .channelSettingsIntent()
                .getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE),
        )
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun notificationBottleHasTransparentBackgroundAndMonochromeInk() {
        val bitmap =
            android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888)
        val drawable = requireNotNull(app.getDrawable(R.drawable.ic_notification_bottle))
        drawable.setBounds(0, 0, 24, 24)
        drawable.draw(android.graphics.Canvas(bitmap))
        assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(0, 0)))
        assertTrue(android.graphics.Color.alpha(bitmap.getPixel(12, 14)) > 0)
        for (x in 0..23) for (y in 0..23) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) > 0) assertEquals(0xFFFFFF, pixel and 0xFFFFFF)
        }
    }

    @Test
    fun referencePreferenceSurvivesNewStoreAndIsSeparateFromVisibility() = runBlocking {
        assertEquals(PumpReference.START, app.settings.settings.first().pumpReference)
        app.reminders.changeOptions(app, 180, PumpReference.END, 8)
        app.settings.bool("intervals", false)
        val reopened = SettingsStore(app, app.db.dao(), app.testStore).settings.first()
        assertEquals(PumpReference.END, reopened.pumpReference)
        assertFalse(reopened.intervals)
        app.reminders.changeOptions(app, 180, PumpReference.START, 8)
        assertFalse(app.settings.settings.first().intervals)
    }
}
