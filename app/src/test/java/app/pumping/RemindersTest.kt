package app.pumping

import android.app.AlarmManager
import android.app.NotificationManager
import android.os.SystemClock
import android.provider.Settings.Global
import androidx.test.core.app.ApplicationProvider
import app.pumping.domain.TimerState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = PumpingApp::class)
class RemindersTest {
    @After
    fun closeApplicationDatabase() {
        ApplicationProvider.getApplicationContext<PumpingApp>().db.close()
    }

    @Test
    fun restoringAndAdjustingKeepsOneAlarmPerDeliveryPath() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PumpingApp>()
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        val at = System.currentTimeMillis() + 3600000
        app.reminders.setPumpReminder(app, at)
        repeat(3) { app.reminders.restore(app) }
        val pumpAlarms =
            alarms.scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == "pump" }
        assertTrue(pumpAlarms.isNotEmpty())
        assertTrue(
            pumpAlarms
                .groupBy { shadowOf(it.operation).savedIntent.data }
                .values
                .all { it.size == 1 }
        )
        app.reminders.setPumpReminder(app, at + 900000)
        app.reminders.restore(app)
        val next =
            alarms.scheduledAlarms.first { shadowOf(it.operation).savedIntent.action == "pump" }
        assertEquals(at + 900000, next.triggerAtTime)
        app.reminders.setPumpReminder(app, 0)
        app.reminders.restore(app)
        assertTrue(
            alarms.scheduledAlarms.none { shadowOf(it.operation).savedIntent.action == "pump" }
        )
    }

    @Test
    fun rebootRecoveryPausesWithoutCountingUncertainTime() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PumpingApp>()
        Global.putInt(app.contentResolver, Global.BOOT_COUNT, 2)
        app.settings.saveTimer(
            TimerState(
                "synthetic",
                System.currentTimeMillis() - 60000,
                3000,
                SystemClock.elapsedRealtime(),
                1,
                true,
            )
        )
        app.reminders.restore(app)
        val t = app.settings.timer.first()
        assertFalse(t.running)
        assertEquals(3000L, t.accumulated)
    }

    @Test
    fun deniedNotificationPermissionDoesNotThrow() {
        val app = ApplicationProvider.getApplicationContext<PumpingApp>()
        if (android.os.Build.VERSION.SDK_INT >= 33)
            shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        app.reminders.notify("pump", "Synthetic reminder", "Synthetic notification")
        if (android.os.Build.VERSION.SDK_INT >= 33)
            assertEquals(
                0,
                shadowOf(app.getSystemService(NotificationManager::class.java))
                    .allNotifications
                    .size,
            )
    }
}
