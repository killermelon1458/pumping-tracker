package app.pumping

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.pumping.reminders.Reminders
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class LegacyReminderApplication : ReminderTestApplication() {
    override fun onCreate() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                        Reminders.LEGACY_CHANNEL,
                        "Original alerts",
                        NotificationManager.IMPORTANCE_NONE,
                    )
                    .apply {
                        setSound(null, null)
                        enableVibration(false)
                    }
            )
        super.onCreate()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = LegacyReminderApplication::class)
class LegacyReminderChannelTest {
    private val app
        get() = ApplicationProvider.getApplicationContext<LegacyReminderApplication>()

    private val manager
        get() = app.getSystemService(NotificationManager::class.java)

    @After
    fun close() = runBlocking {
        app.job.cancelAndJoin()
        app.db.close()
        app.preferenceFile.delete()
        Unit
    }

    @Test
    fun existingBlockedChannelIsPreservedWithoutSilentReplacement() {
        repeat(3) { app.reminders.createChannels() }
        assertTrue(app.reminders.usesLegacyChannel())
        assertNull(manager.getNotificationChannel(Reminders.PUMP_CHANNEL))
        val channel = manager.getNotificationChannel(Reminders.LEGACY_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_NONE, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(app.reminders.notify("pump", "Synthetic", "Synthetic"))
        assertFalse(app.reminders.notify("timer", "Synthetic", "Synthetic"))
    }

    @Test
    fun explicitOptInCreatesHighChannelAndLeavesOriginalUntouched() {
        app.reminders.createDedicatedChannel()
        assertFalse(app.reminders.usesLegacyChannel())
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(Reminders.PUMP_CHANNEL).importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_NONE,
            manager.getNotificationChannel(Reminders.LEGACY_CHANNEL).importance,
        )
    }
}
