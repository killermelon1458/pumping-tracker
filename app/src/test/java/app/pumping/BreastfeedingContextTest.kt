package app.pumping

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pumping.data.*
import app.pumping.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = PumpingApp::class)
class BreastfeedingContextTest {
    private lateinit var db: PumpDatabase
    private lateinit var repo: Repository
    private lateinit var backup: Backup
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun memory() = Room.inMemoryDatabaseBuilder(context,PumpDatabase::class.java).allowMainThreadQueries().build()
    private fun store(r: Repository) = SettingsStore(context,r.dao)
    private fun fixture() = javaClass.classLoader!!.getResourceAsStream("legacy/synthetic_fixture.pumping")!!.bufferedReader().use { it.readText() }
    @Before fun setup() { db=memory();repo=Repository(db);backup=Backup(repo,store(repo)) }
    @After fun close() { db.close() }
    private fun session(id: String, status: BreastfeedingStatus=BreastfeedingStatus.UNKNOWN) = Session(
        id=id,start=1000,end=61000,activeSeconds=60,volume=10000,notes="Synthetic nursing context",breastfedBefore=status)

    @Test fun allStatusesAndExistingInventoryLegacyAndTombstonesRoundTrip() = runBlocking {
        val legacy=LegacyBackup(repo,store(repo));legacy.import(legacy.prepare(fixture()))
        BreastfeedingStatus.entries.forEach { repo.saveSession(session("synthetic-${it.name}",it)) }
        val yes="synthetic-YES"
        repo.saveSession(repo.dao.session(yes)!!,listOf(Allocation(7000,"Fridge",4)))
        val bag=repo.dao.linkedBags(yes).single()
        repo.useBag(bag.id,1000,"Feeding",bag.stored+1,"Synthetic use")
        repo.saveSession(session("synthetic-deleted",BreastfeedingStatus.NO))
        repo.deleteSession("synthetic-deleted")
        val text=backup.export();assertEquals(3,JSONObject(text).getInt("version"))
        val other=memory()
        try {
            val r=Repository(other);val b=Backup(r,store(r));b.import(text);b.import(text)
            assertEquals(repo.dao.allSessions().toSet(),r.dao.allSessions().toSet())
            assertEquals(repo.dao.allBags(),r.dao.allBags())
            assertEquals(repo.dao.allMovements(),r.dao.allMovements())
            assertEquals(repo.dao.deletions(),r.dao.deletions())
            assertEquals(repo.dao.legacyRecords().toSet(),r.dao.legacyRecords().toSet())
            assertEquals(repo.dao.legacyMetadata().toSet(),r.dao.legacyMetadata().toSet())
            assertEquals(repo.dao.currentLegacyPreferences(),r.dao.currentLegacyPreferences())
            assertEquals(6000,r.dao.remaining(bag.id))
        } finally { other.close() }
    }

    @Test fun v1ImportsAsUnknownAndIsIdempotent() = runBlocking {
        repo.saveSession(session("synthetic-v1",BreastfeedingStatus.NO))
        val v1=JSONObject(backup.export()).put("version",1)
        v1.remove("pump_profiles")
        v1.getJSONArray("sessions").getJSONObject(0).remove("pumpProfileId")
        v1.getJSONArray("sessions").getJSONObject(0).remove("breastfedBefore")
        val other=memory()
        try {
            val r=Repository(other);val b=Backup(r,store(r));b.import(v1.toString());b.import(v1.toString())
            assertEquals(BreastfeedingStatus.UNKNOWN,r.dao.session("synthetic-v1")!!.breastfedBefore)
            r.saveSession(r.dao.session("synthetic-v1")!!.copy(breastfedBefore=BreastfeedingStatus.YES))
            assertTrue(runCatching { b.import(v1.toString()) }.isFailure)
            assertEquals(BreastfeedingStatus.YES,r.dao.session("synthetic-v1")!!.breastfedBefore)
        } finally { other.close() }
    }

    @Test fun unsupportedVersionsFieldsAndInvalidStatusesNeverPartiallyImport() = runBlocking {
        repo.saveSession(session("synthetic-valid",BreastfeedingStatus.YES))
        val text=backup.export()
        val malformed=listOf(
            JSONObject(text).put("version",4),
            JSONObject(text).put("unrecognizedProfiles",org.json.JSONArray()),
            JSONObject(text).apply { getJSONArray("sessions").getJSONObject(0).put("pumpProfileId","future") },
            JSONObject(text).apply { getJSONArray("sessions").getJSONObject(0).put("breastfedBefore","SOMETIMES") },
            JSONObject(text).apply { getJSONArray("sessions").getJSONObject(0).remove("breastfedBefore") },
        )
        malformed.forEach { root ->
            val other=memory()
            try {
                val r=Repository(other);r.saveSession(session("existing"))
                val b=Backup(r,store(r));val before=b.export()
                assertTrue(runCatching { b.import(root.toString()) }.isFailure)
                assertEquals(before,b.export())
            } finally { other.close() }
        }
    }

    @Test fun v2FlagConflictsAndBrokenBagReferencesRollBack() = runBlocking {
        repo.saveSession(session("synthetic-a",BreastfeedingStatus.YES),listOf(Allocation(5000,"Fridge",4)))
        val good=backup.export()
        val bad=JSONObject(good).apply { getJSONArray("bags").getJSONObject(0).put("sourceSession","missing") }
        val other=memory()
        try {
            val r=Repository(other);val b=Backup(r,store(r))
            assertTrue(runCatching { b.import(bad.toString()) }.isFailure);assertTrue(r.dao.allSessions().isEmpty())
            b.import(good)
            val changed=JSONObject(good).apply { getJSONArray("sessions").getJSONObject(0).put("breastfedBefore","NO") }
            val before=b.export();assertTrue(runCatching { b.import(changed.toString()) }.isFailure)
            assertEquals(before,b.export())
        } finally { other.close() }
    }

    @Test fun legacyMergeKeepsExplicitContextAndDeletionMarkers() = runBlocking {
        val legacy=LegacyBackup(repo,store(repo));val plan=legacy.prepare(fixture());legacy.import(plan)
        assertTrue(repo.dao.allSessions().all { it.breastfedBefore==BreastfeedingStatus.UNKNOWN })
        val s=repo.dao.allSessions().first();repo.saveSession(s.copy(breastfedBefore=BreastfeedingStatus.YES))
        legacy.import(legacy.prepare(fixture()))
        assertEquals(BreastfeedingStatus.YES,repo.dao.session(s.id)!!.breastfedBefore)
        assertTrue(runCatching { legacy.export() }.isFailure)
        repo.deleteSession(s.id);legacy.import(legacy.prepare(fixture()))
        assertNull(repo.dao.session(s.id));assertTrue(repo.dao.isDeleted("session",s.id))
    }

    @Test fun explicitContextSurvivesDiskReopenAndEditWithoutTimestampChanges() = runBlocking {
        val name="synthetic-context-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context,PumpDatabase::class.java,name).addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5).allowMainThreadQueries().build()
        var disk=open();val original=session("synthetic-persistent",BreastfeedingStatus.YES)
        try {
            Repository(disk).saveSession(original);disk.close();disk=open()
            val r=Repository(disk);assertEquals(original,r.dao.session(original.id))
            r.saveSession(original.copy(notes="Synthetic edit"));val saved=r.dao.session(original.id)!!
            assertEquals(BreastfeedingStatus.YES,saved.breastfedBefore)
            assertEquals(original.start,saved.start);assertEquals(original.end,saved.end);assertEquals(original.activeSeconds,saved.activeSeconds)
        } finally { disk.close();context.deleteDatabase(name) }
    }
}
