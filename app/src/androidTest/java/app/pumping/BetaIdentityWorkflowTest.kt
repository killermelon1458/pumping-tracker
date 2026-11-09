package app.pumping

import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

@RunWith(AndroidJUnit4::class)
class BetaIdentityWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun app() = compose.activity.application as PumpingApp

    @Before
    fun resetSyntheticData() = runBlocking {
        check(app() is SyntheticTestApplication)
        app().db.clearAllTables()
        app().settings.string("unit", "oz")
        app().reminders.setPumpReminder(app(), 0)
    }

    @Test
    fun permanentIdUsesExistingNamespaceAndRegisteredComponents() {
        val app = app()
        assertEquals("dev.killermelon1458.pumping.debug", app.packageName)
        assertEquals(app.packageName, BuildConfig.APPLICATION_ID)
        assertEquals("1.4.3-dev", BuildConfig.VERSION_NAME)
        assertEquals(10, BuildConfig.VERSION_CODE)
        assertEquals("Pumping Dev", app.getString(R.string.app_name))
        assertTrue(BuildConfig.DEBUG)
        assertEquals("app.pumping", MainActivity::class.java.`package`!!.name)
        val pm = app.packageManager
        val info = pm.getApplicationInfo(app.packageName, 0)
        assertEquals(PumpingApp::class.java.name, info.className)
        val main = pm.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertEquals(app.packageName, main.packageName)
        assertEquals(MainActivity::class.java.name, main.name)
        assertTrue(main.exported)
        assertFalse(pm.getReceiverInfo(ComponentName(app, AlarmReceiver::class.java), 0).exported)
        assertTrue(pm.getReceiverInfo(ComponentName(app, RestoreReceiver::class.java), 0).exported)
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val provider = pm.resolveContentProvider("$testPackage.synthetic-backup", 0)
        assertEquals("app.pumping.SyntheticBackupProvider", provider!!.name)
        assertEquals(
            app.packageName,
            app.reminders
                .channelSettingsIntent()
                .getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE),
        )
    }

    @Test
    fun profileDisplaysDevelopmentVersion() {
        compose.onNodeWithText("Profile").performClick()
        val version = "Pumping ${BuildConfig.VERSION_NAME} · Offline, with no account or subscription"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(version))
        compose.onNodeWithText(version).assertIsDisplayed()
    }

    @Test
    fun creatingAndEditingSessionPreservesLinkedBag() {
        compose.waitForIdle()
        compose.onNodeWithText("Quick Log").performClick()
        compose.onNodeWithTag("Volume (oz)").performTextInput("3")
        compose.onNodeWithTag("Stash It").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(15000) {
            runBlocking { app().repository.dao.allSessions().size == 1 } &&
                compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty()
        }
        val id = runBlocking { app().repository.dao.allSessions().single().id }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("session-$id"))
        compose.onNodeWithTag("session-$id").performClick()
        compose.onNodeWithTag("Volume (oz)").performTextReplacement("4")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(15000) {
            runBlocking { app().repository.dao.session(id)?.volume == Volume.parse("4", "oz") } &&
                compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty()
        }
        compose.activityRule.scenario.recreate()
        runBlocking {
            val bag = app().repository.dao.allBags().single()
            assertEquals(id, bag.sourceSession)
            assertEquals(Volume.parse("3", "oz"), bag.initial)
            assertEquals(Volume.parse("4", "oz"), app().repository.dao.session(id)!!.volume)
        }
    }

    @Test
    fun persistentRecordsPreferencesRelationshipsAndNativeBackupSurviveReopen() = runBlocking {
        val app = app()
        val name = "synthetic-beta-${UUID.randomUUID()}.db"
        val file = File(app.cacheDir, "synthetic-beta-${UUID.randomUUID()}.preferences_pb")
        var job = SupervisorJob()
        fun store() =
            SettingsStore(
                app,
                isolatedStore =
                    PreferenceDataStoreFactory.create(
                        scope = CoroutineScope(job + Dispatchers.IO),
                        produceFile = { file },
                    ),
            )
        fun database() =
            Room.databaseBuilder(app, PumpDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
        var db = database()
        val other = Room.inMemoryDatabaseBuilder(app, PumpDatabase::class.java).build()
        try {
            var settings = store()
            val now = System.currentTimeMillis()
            val session =
                Session(
                    start = now - 1200000,
                    end = now,
                    activeSeconds = 1200,
                    volume = 100000,
                    notes = "Synthetic beta persistence",
                )
            val repo = Repository(db)
            repo.saveSession(session, listOf(Allocation(100000, "Fridge", 4)))
            val bag = repo.dao.allBags().single()
            repo.useBag(bag.id, 20000, "Feeding", now, "Synthetic")
            settings.string("inputUnit", "mL")
            settings.string("displayUnit", "oz")
            settings.reminderOptions(180, PumpReference.END, 8)
            val alarm = PumpAlarmState(true, now + 180000, newId(), "synthetic-beta-basis")
            settings.savePumpAlarm(alarm)
            val snapshot = Backup(repo, settings).export()
            val movements = repo.dao.allMovements()
            db.close()
            job.cancelAndJoin()
            job = SupervisorJob()
            settings = store()
            db = database()
            assertEquals(session, db.dao().session(session.id))
            assertEquals(bag, db.dao().allBags().single())
            assertEquals(movements, db.dao().allMovements())
            assertEquals(80000L, db.dao().remaining(bag.id))
            assertEquals("mL", settings.settings.first().inputUnit)
            assertEquals("oz", settings.settings.first().displayUnit)
            assertEquals(PumpReference.END, settings.settings.first().pumpReference)
            assertEquals(alarm, settings.pumpAlarm.first())
            Backup(Repository(other), settings).import(snapshot)
            assertEquals(session, other.dao().session(session.id))
            assertEquals(bag, other.dao().allBags().single())
            assertEquals(movements, other.dao().allMovements())
        } finally {
            db.close()
            other.close()
            job.cancelAndJoin()
            app.deleteDatabase(name)
            file.delete()
        }
    }
}
