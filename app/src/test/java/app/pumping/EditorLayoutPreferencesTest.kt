package app.pumping

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pumping.data.*
import app.pumping.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35], application=PumpingApp::class)
class EditorLayoutPreferencesTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private lateinit var file:File
    private lateinit var job:Job
    private lateinit var preferences:DataStore<Preferences>
    private lateinit var store:SettingsStore
    private lateinit var db:PumpDatabase
    private fun open(){job=SupervisorJob();preferences=PreferenceDataStoreFactory.create(scope=CoroutineScope(job+Dispatchers.IO),produceFile={file});store=SettingsStore(context,db.dao(),preferences)}
    @Before fun setup(){db=Room.inMemoryDatabaseBuilder(context,PumpDatabase::class.java).allowMainThreadQueries().build();file=File(context.cacheDir,"layout-${UUID.randomUUID()}.preferences_pb");open()}
    @After fun close()=runBlocking{job.cancelAndJoin();db.close();file.delete();Unit}
    @Test fun layoutsPersistIndependentlyAcrossStoreRestart()=runBlocking {
        val pump=EditorArrangement.default(EditorKind.PUMP).move("notes",0).visible(EditorKind.PUMP,"breastfeeding",false)
        val bag=EditorArrangement.default(EditorKind.BAG).move("volume",0)
        store.saveEditorLayout(EditorKind.PUMP,pump);store.saveEditorLayout(EditorKind.BAG,bag)
        job.cancelAndJoin();open()
        assertEquals(pump,store.editorLayout(EditorKind.PUMP).first())
        assertEquals(bag,store.editorLayout(EditorKind.BAG).first())
        assertEquals(EditorArrangement.default(EditorKind.USAGE),store.editorLayout(EditorKind.USAGE).first())
    }
    @Test fun invalidStoredLayoutRecoversWithoutChangingOtherPreferences()=runBlocking {
        store.string("inputUnit","mL");store.string("displayUnit","oz")
        preferences.edit{it[stringPreferencesKey("editor-layout-pump")]="invalid"}
        assertEquals(EditorArrangement.default(EditorKind.PUMP),store.editorLayout(EditorKind.PUMP).first())
        assertEquals("mL",store.settings.first().inputUnit);assertEquals("oz",store.settings.first().displayUnit)
    }
    @Test fun layoutSavingCannotAffectRecordsOrNativeBackupV4()=runBlocking {
        val repo=Repository(db);val session=Session(start=100000,end=160000,activeSeconds=60,volume=10000,notes="Synthetic")
        repo.saveSession(session,listOf(Allocation(10000,"Fridge",4)))
        val before=Backup(repo,store).export()
        store.saveEditorLayout(EditorKind.PUMP,EditorArrangement.default(EditorKind.PUMP).visible(EditorKind.PUMP,"notes",false))
        assertEquals(before,Backup(repo,store).export())
        assertEquals(session,repo.dao.session(session.id));assertEquals(6,db.openHelper.readableDatabase.version)
    }
    @Test fun normalizationKeepsNewRequiredAndOptionalSectionsDiscoverable()=runBlocking {
        preferences.edit{it[stringPreferencesKey("editor-layout-pump")]="1|milk,timing|timing,breastfeeding"}
        val result=store.editorLayout(EditorKind.PUMP).first()
        assertEquals(EditorKind.PUMP.sections.size,result.order.size)
        assertFalse("power" in result.hidden);assertFalse("timing" in result.hidden)
        store.saveEditorLayout(EditorKind.PUMP,result)
        assertEquals(result,store.editorLayout(EditorKind.PUMP).first())
    }
    @Test fun globalPickerDefaultsToClockAndPersistsIndependentlyOfEveryLayout()=runBlocking {
        assertEquals(TimePickerStyle.CLOCK_FACE,store.timePickerStyle.first())
        assertEquals(TimePickerStyle.CLOCK_FACE,store.settings.first().timePickerStyle)
        val layout=EditorArrangement.default(EditorKind.PUMP).visible(EditorKind.PUMP,"notes",false)
        store.saveEditorLayout(EditorKind.PUMP,layout)
        store.saveTimePickerStyle(TimePickerStyle.SCROLL_WHEELS)
        job.cancelAndJoin();open()
        assertEquals(TimePickerStyle.SCROLL_WHEELS,store.timePickerStyle.first())
        assertEquals(TimePickerStyle.SCROLL_WHEELS,store.settings.first().timePickerStyle)
        assertEquals(layout,store.editorLayout(EditorKind.PUMP).first())
        store.saveEditorLayout(EditorKind.PUMP,EditorArrangement.default(EditorKind.PUMP))
        assertEquals(TimePickerStyle.SCROLL_WHEELS,store.timePickerStyle.first())
        store.saveTimePickerStyle(TimePickerStyle.CLOCK_FACE)
        assertEquals(EditorArrangement.default(EditorKind.USAGE),store.editorLayout(EditorKind.USAGE).first())
    }
    @Test fun pickerStyleCannotModifyTimestampsUnitsRemindersTimerOrNativeBackup()=runBlocking {
        val repo=Repository(db)
        val session=Session(start=100000,end=160000,activeSeconds=60,volume=10000,notes="Synthetic picker settings")
        repo.saveSession(session,listOf(Allocation(10000,"Fridge",4)))
        store.string("inputUnit","mL");store.string("displayUnit","oz")
        store.reminderOptions(195,PumpReference.END,7)
        val timer=TimerState(id="synthetic-paused",start=100000,accumulated=30000)
        store.saveTimer(timer)
        val before=Backup(repo,store).export();val settings=store.settings.first()
        for (style in TimePickerStyle.entries) {
            store.saveTimePickerStyle(style)
            assertEquals(settings.copy(timePickerStyle=style),store.settings.first())
            assertEquals(timer,store.timer.first());assertEquals(session,repo.dao.session(session.id))
            assertEquals(before,Backup(repo,store).export())
        }
    }
    @Test fun invalidStoredPickerStyleRecoversToClockWithoutResettingLayouts()=runBlocking {
        val layout=EditorArrangement.default(EditorKind.BAG).move("notes",0)
        store.saveEditorLayout(EditorKind.BAG,layout)
        preferences.edit { it[stringPreferencesKey("timePickerStyle")]="unknown-future-style" }
        assertEquals(TimePickerStyle.CLOCK_FACE,store.timePickerStyle.first())
        assertEquals(TimePickerStyle.CLOCK_FACE,store.settings.first().timePickerStyle)
        assertEquals(layout,store.editorLayout(EditorKind.BAG).first())
    }

    @Test fun nativeAndLegacyRestoresLeaveDeviceLocalPresentationPreferencesAlone()=runBlocking {
        val repo=Repository(db)
        val backup=Backup(repo,store).export()
        val layout=EditorArrangement.default(EditorKind.PUMP).move("milk",0).visible(EditorKind.PUMP,"breastfeeding",false)
        store.saveEditorLayout(EditorKind.PUMP,layout)
        store.saveTimePickerStyle(TimePickerStyle.SCROLL_WHEELS)
        Backup(repo,store).import(backup,applyLegacySettings=true)
        val legacy=LegacyBackup(repo,store)
        val fixture=javaClass.classLoader!!.getResourceAsStream("legacy/synthetic_fixture.pumping")!!.bufferedReader().use{it.readText()}
        legacy.import(legacy.prepare(fixture,java.time.ZoneId.of("America/Chicago")),applySettings=true)
        assertEquals(TimePickerStyle.SCROLL_WHEELS,store.settings.first().timePickerStyle)
        assertEquals(layout,store.editorLayout(EditorKind.PUMP).first())
        assertFalse(repo.dao.allSessions().isEmpty())
        assertEquals(6,db.openHelper.readableDatabase.version)
    }

}
