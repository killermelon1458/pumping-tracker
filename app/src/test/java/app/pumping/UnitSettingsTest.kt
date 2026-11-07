package app.pumping

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pumping.data.*
import app.pumping.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = PumpingApp::class)
class UnitSettingsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var db: PumpDatabase
    private lateinit var file: File
    private lateinit var job: Job
    private lateinit var preferences: androidx.datastore.core.DataStore<Preferences>
    private lateinit var store: SettingsStore

    private fun open() {
        job = SupervisorJob()
        preferences =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + Dispatchers.IO),
                produceFile = { file },
            )
        store = SettingsStore(context, db.dao(), preferences)
    }

    @Before
    fun setup() {
        db =
            Room.inMemoryDatabaseBuilder(context, PumpDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        file = File(context.cacheDir, "units-${UUID.randomUUID()}.preferences_pb")
        open()
    }

    @After
    fun close() = runBlocking {
        job.cancelAndJoin()
        db.close()
        file.delete()
        Unit
    }

    @Test
    fun oldPreferenceInitializesBothAndIndependentChangesPersist() = runBlocking {
        preferences.edit { it[stringPreferencesKey("unit")] = "mL" }
        assertEquals("mL", store.settings.first().inputUnit)
        assertEquals("mL", store.settings.first().displayUnit)
        store.string("inputUnit", "oz")
        assertEquals("mL", store.settings.first().displayUnit)
        job.cancelAndJoin()
        open()
        assertEquals("oz", store.settings.first().inputUnit)
        assertEquals("mL", store.settings.first().displayUnit)
        store.string("displayUnit", "oz")
        assertEquals("oz", store.settings.first().inputUnit)
        store.string("inputUnit", "mL")
        assertEquals("oz", store.settings.first().displayUnit)
    }

    @Test
    fun oldImportedPreferenceAcknowledgementIsPreserved() = runBlocking {
        db.dao().putLegacyPreferences(LegacyPreferences(token = "old", json = "{\"unit\":\"oz\"}"))
        preferences.edit {
            it[stringPreferencesKey("unit")] = "mL"
            it[stringPreferencesKey("legacy-ack-unit")] = "old"
        }
        assertEquals("mL", store.settings.first().inputUnit)
        assertEquals("mL", store.settings.first().displayUnit)
    }

    @Test
    fun independentChoicesOverrideOnlyTheirImportedSetting() = runBlocking {
        db.dao().putLegacyPreferences(LegacyPreferences(token = "new", json = "{\"unit\":\"mL\"}"))
        store.string("inputUnit", "oz")
        assertEquals("oz", store.settings.first().inputUnit)
        assertEquals("mL", store.settings.first().displayUnit)
        store.string("displayUnit", "oz")
        assertEquals("oz", store.settings.first().inputUnit)
        job.cancelAndJoin()
        open()
        assertEquals("oz", store.settings.first().inputUnit)
        assertEquals("oz", store.settings.first().displayUnit)
    }

    @Test
    fun canonicalQuantitiesRoundTripWithoutDrift() {
        val values =
            listOf(0L, 1L, 2L, 29574L, 100000L, Volume.MAX) +
                List(1000) { kotlin.random.Random(it).nextLong(Volume.MAX) }
        for (quantity in values) {
            var text = Volume.format(quantity, "oz", 5)
            repeat(10) {
                text = Volume.convertInput(text, "oz", "mL")
                assertEquals(quantity, Volume.parse(text, "mL"))
                text = Volume.convertInput(text, "mL", "oz")
                assertEquals(quantity, Volume.parse(text, "oz"))
            }
        }
        assertEquals("", Volume.convertInput("", "oz", "mL"))
    }

    @Test
    fun savedDataAndNativeBackupRetainIndependentSettings() = runBlocking {
        val repo = Repository(db)
        val quantity = Volume.parse("100", "mL")
        val session = Session(start = 1000, end = 61000, activeSeconds = 60, volume = quantity)
        repo.saveSession(session, listOf(Allocation(quantity, "Fridge", 4)))
        store.string("inputUnit", "mL")
        store.string("displayUnit", "oz")
        assertEquals(
            "3.38",
            Volume.format(db.dao().session(session.id)!!.volume, store.settings.first().displayUnit),
        )
        val exported = Backup(repo, store).export()
        val prefs = JSONObject(exported).getJSONObject("preferences_snapshot")
        assertEquals("mL", prefs.getString("inputUnit"))
        assertEquals("oz", prefs.getString("displayUnit"))
        store.string("inputUnit", "oz")
        store.string("displayUnit", "mL")
        Backup(repo, store).import(exported, applyLegacySettings = true)
        assertEquals("mL", store.settings.first().inputUnit)
        assertEquals("oz", store.settings.first().displayUnit)
        assertEquals(quantity, db.dao().allBags().single().initial)
        assertEquals(quantity, db.dao().session(session.id)!!.volume)
    }

    @Test
    fun enteringOuncesAndDisplayingMillilitersUsesUSConversion() {
        assertEquals("29.57", Volume.format(Volume.parse("1", "oz"), "mL"))
        assertEquals("1", Volume.format(Volume.parse("29.57353", "mL"), "oz"))
    }
}
