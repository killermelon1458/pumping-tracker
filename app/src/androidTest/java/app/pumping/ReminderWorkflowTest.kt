package app.pumping

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pumping.data.*
import app.pumping.domain.*
import app.pumping.reminders.Reminders
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun app() = compose.activity.application as PumpingApp

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }
    }

    @Before
    fun setup() {
        check(app() is SyntheticTestApplication)
        app().db.clearAllTables()
        runBlocking {
            app().reminders.setPumpReminder(app(), 0)
            app().reminders.changeOptions(app(), 180, PumpReference.START, 7)
        }
    }

    @After fun cancel() = runBlocking { app().reminders.setPumpReminder(app(), 0) }

    private fun seed(): Session = runBlocking {
        val now = System.currentTimeMillis()
        Session(start = now - 20 * 60000, end = now, activeSeconds = 1200, volume = 1000).also {
            app().repository.saveSession(it)
        }
    }

    @Test
    fun profileReferencePersistsAndUpdatesCounterAndAutomaticDeadline() {
        val session = seed()
        runBlocking { app().reminders.enableAutomatic(app()) }
        compose.waitForIdle()
        compose.onNodeWithText("Profile").performClick()
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Reminders · every 180 min"))
        compose.onNodeWithText("Reminders · every 180 min").performClick()
        compose.onNodeWithText("End of last pump").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10000) {
            runBlocking {
                app().settings.settings.first().pumpReference == PumpReference.END &&
                    app().settings.pumpAlarm.first().due == session.end + 180 * 60000L
            } && compose.onAllNodesWithText("Reminder Settings").fetchSemanticsNodes().isEmpty()
        }
        runBlocking {
            assertEquals(session.end + 180 * 60000L, app().settings.pumpAlarm.first().due)
            assertEquals(session, app().repository.dao.session(session.id))
        }
        compose.onNodeWithText("Pump").performClick()
        compose.onNodeWithText("ago · ended", substring = true).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        assertEquals(
            PumpReference.END,
            runBlocking { app().settings.settings.first().pumpReference },
        )
    }

    @Test
    fun actualAlarmPostsWhileForegroundAndExpandedActionsWorkInBackground() {
        seed()
        if (Build.VERSION.SDK_INT >= 33)
            shell("pm grant app.pumping ${Manifest.permission.POST_NOTIFICATIONS}")
        if (Build.VERSION.SDK_INT >= 31) shell("appops set app.pumping SCHEDULE_EXACT_ALARM allow")
        assertTrue(app().reminders.canScheduleExact())
        val manager = app().getSystemService(NotificationManager::class.java)
        runBlocking { app().reminders.setPumpReminder(app(), System.currentTimeMillis() + 2500) }
        compose.waitUntil(30000) {
            manager.activeNotifications.any { it.id == Reminders.PUMP_NOTIFICATION_ID }
        }
        val notification =
            manager.activeNotifications
                .single { it.id == Reminders.PUMP_NOTIFICATION_ID }
                .notification
        assertEquals(
            "It's time to pump",
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertTrue(
            notification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                .toString()
                .startsWith("Last pump started")
        )
        assertEquals(
            listOf("Snooze 5 min", "Dismiss"),
            notification.actions.map { it.title.toString() },
        )
        val baseDue = runBlocking { app().settings.pumpAlarm.first().due }
        shell("input keyevent KEYCODE_HOME")
        notification.actions[0].actionIntent.send()
        compose.waitUntil(15000) {
            runBlocking { app().settings.pumpAlarm.first().snoozeAt > 0 } &&
                manager.activeNotifications.none { it.id == Reminders.PUMP_NOTIFICATION_ID }
        }
        assertTrue(manager.activeNotifications.none { it.id == Reminders.PUMP_NOTIFICATION_ID })
        val snoozed = runBlocking { app().settings.pumpAlarm.first() }
        assertEquals(baseDue, snoozed.due)
        assertTrue(
            snoozed.snoozeAt in
                (System.currentTimeMillis() + 280000)..(System.currentTimeMillis() + 310000)
        )
        // An obsolete action cannot dismiss the future snooze or replace its deadline.
        notification.actions[1].actionIntent.send()
        compose.waitForIdle()
        assertEquals(snoozed, runBlocking { app().settings.pumpAlarm.first() })
    }
}
