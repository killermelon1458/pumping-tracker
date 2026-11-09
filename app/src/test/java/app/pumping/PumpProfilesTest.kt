package app.pumping

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pumping.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35], application=PumpingApp::class)
class PumpProfilesTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun memory()=Room.inMemoryDatabaseBuilder(context,PumpDatabase::class.java).allowMainThreadQueries().build()
    private lateinit var db:PumpDatabase
    private lateinit var repo:Repository
    private fun store(r:Repository)=SettingsStore(context,r.dao)
    private fun backup(r:Repository)=Backup(r,store(r))
    private fun session(id:String,pump:String?=null,updated:Long=1,status:BreastfeedingStatus=BreastfeedingStatus.UNKNOWN)=Session(
        id=id,start=1000,end=61000,activeSeconds=60,volume=100000,notes="Synthetic session",created=updated,updated=updated,breastfedBefore=status,pumpProfileId=pump)
    @Before fun setup(){db=memory();repo=Repository(db)}
    @After fun close(){db.close()}

    @Test fun allTypesHaveOptionalDetailsAndUsefulUniqueNames()=runBlocking {
        PumpType.entries.forEach { type ->
            val name=suggestedPumpNickname(type,"","",repo.dao.allPumpProfiles())
            repo.savePumpProfile(PumpProfile(type=type,nickname=name))
        }
        assertEquals(4,repo.dao.allPumpProfiles().size)
        assertEquals("Stationary Spectra",suggestedPumpNickname(PumpType.STATIONARY," Spectra ","",emptyList()))
        assertEquals("Wearable Momcozy",suggestedPumpNickname(PumpType.MOBILE_OR_WEARABLE,"Momcozy","",emptyList()))
        assertEquals("Travel Custom",suggestedPumpNickname(PumpType.OTHER,"Custom","Travel",emptyList()))
        assertEquals("Stationary (2)",suggestedPumpNickname(PumpType.STATIONARY,"","",repo.dao.allPumpProfiles()))
        assertTrue(runCatching{repo.savePumpProfile(PumpProfile(nickname="stationary"))}.isFailure)
        assertEquals(4,repo.dao.allPumpProfiles().size)
        assertEquals("Not specified",BreastfeedingStatus.UNKNOWN.displayLabel())
        assertEquals("UNKNOWN",BreastfeedingStatus.UNKNOWN.name)
    }

    @Test fun renamingAndArchivingPreserveHistoryQuantityTimesAndLedger()=runBlocking {
        val p=PumpProfile(nickname="Synthetic pump",brand="Brand",model="Model",notes="Synthetic pump note")
        repo.savePumpProfile(p)
        val s=session("linked",p.id,status=BreastfeedingStatus.YES)
        repo.saveSession(s,listOf(Allocation(50000,"Fridge",4)))
        val bag=repo.dao.allBags().single();repo.useBag(bag.id,10000,"Feeding",62000,"Synthetic feeding")
        val movements=repo.dao.allMovements();val bags=repo.dao.allBags()
        repo.savePumpProfile(p.copy(nickname="Renamed",type=PumpType.MOBILE_OR_WEARABLE,brand="Other brand"))
        repo.archivePumpProfile(p.id,true)
        assertEquals(s,repo.dao.session(s.id));assertEquals(bags,repo.dao.allBags());assertEquals(movements,repo.dao.allMovements())
        assertEquals(40000,repo.dao.remaining(bag.id))
        repo.saveSession(s) // Keeping the original archived pump is permitted.
        assertTrue(runCatching{repo.saveSession(session("new",p.id))}.isFailure)
        assertTrue(runCatching{repo.dao.clearPumpProfiles()}.isFailure) // FK restricts hard deletion.
        assertEquals(s,repo.dao.session(s.id))
        repo.archivePumpProfile(p.id,false);assertFalse(repo.dao.pumpProfile(p.id)!!.archived)
    }

    @Test fun lastSavedSelectionIncludesExplicitUnspecifiedAndIgnoresArchived()=runBlocking {
        val p=PumpProfile(nickname="Synthetic pump");repo.savePumpProfile(p)
        assertNull(repo.defaultPumpProfile())
        repo.saveSession(session("first",p.id,100));assertEquals(p.id,repo.defaultPumpProfile())
        repo.archivePumpProfile(p.id,true);assertNull(repo.defaultPumpProfile())
        repo.archivePumpProfile(p.id,false);assertEquals(p.id,repo.defaultPumpProfile())
        repo.saveSession(session("explicit-none",null,200));assertNull(repo.defaultPumpProfile())
        assertEquals(p.id,repo.dao.session("first")!!.pumpProfileId)
    }

    @Test fun v3RoundTripIncludesArchivedProfilesAssociationsLegacyAndDeletions()=runBlocking {
        val fixture=javaClass.classLoader!!.getResourceAsStream("legacy/synthetic_fixture.pumping")!!.bufferedReader().use{it.readText()}
        LegacyBackup(repo,store(repo)).let{it.import(it.prepare(fixture))}
        PumpType.entries.forEachIndexed { index,type ->
            val p=PumpProfile(type=type,nickname="Synthetic $index",customType=if(type==PumpType.OTHER)"Custom" else "",brand="Brand",model="Model",notes="Synthetic profile note")
            repo.savePumpProfile(p)
            repo.saveSession(session("s-$index",p.id,index+100L,BreastfeedingStatus.NO),listOf(Allocation(5000,"Freezer",180)))
            if(index==0)repo.archivePumpProfile(p.id,true)
        }
        val iosSession=repo.dao.allSessions().first { !it.id.startsWith("s-") }
        val activePump=repo.dao.allPumpProfiles().first { !it.archived }
        repo.saveSession(iosSession.copy(pumpProfileId=activePump.id))
        LegacyBackup(repo,store(repo)).let { it.import(it.prepare(fixture)) }
        assertEquals(activePump.id,repo.dao.session(iosSession.id)!!.pumpProfileId)
        repo.saveSession(session("removed"));repo.deleteSession("removed")
        val text=backup(repo).export();assertEquals(3,JSONObject(text).getInt("version"))
        val other=memory()
        try {
            val r=Repository(other);val b=backup(r);b.import(text);b.import(text)
            assertEquals(repo.dao.allPumpProfiles(),r.dao.allPumpProfiles())
            assertEquals(repo.dao.allSessions().toSet(),r.dao.allSessions().toSet())
            assertEquals(repo.dao.allBags(),r.dao.allBags());assertEquals(repo.dao.allMovements().toSet(),r.dao.allMovements().toSet())
            assertEquals(repo.dao.deletions(),r.dao.deletions());assertEquals(repo.dao.legacyRecords().toSet(),r.dao.legacyRecords().toSet())
            assertEquals(repo.dao.legacyMetadata().toSet(),r.dao.legacyMetadata().toSet())
        } finally{other.close()}
    }

    @Test fun v1AndV2RemainImportableWithoutInventingPumpAssignments()=runBlocking {
        repo.saveSession(session("old",status=BreastfeedingStatus.YES))
        for(version in listOf(1,2)) {
            val root=JSONObject(backup(repo).export()).put("version",version);root.remove("pump_profiles")
            val row=root.getJSONArray("sessions").getJSONObject(0);row.remove("pumpProfileId")
            if(version==1)row.remove("breastfedBefore")
            val other=memory()
            try {
                val r=Repository(other);r.savePumpProfile(PumpProfile(nickname="Existing active pump"))
                backup(r).import(root.toString());backup(r).import(root.toString())
                val s=r.dao.session("old")!!;assertNull(s.pumpProfileId)
                assertEquals(if(version==1)BreastfeedingStatus.UNKNOWN else BreastfeedingStatus.YES,s.breastfedBefore)
            }finally{other.close()}
        }
    }

    @Test fun invalidReferencesConflictsAndFutureSchemaRollBackEverything()=runBlocking {
        val p=PumpProfile(nickname="Synthetic pump");repo.savePumpProfile(p);repo.saveSession(session("one",p.id))
        val text=backup(repo).export()
        val invalid=listOf(
            JSONObject(text).put("version",4),
            JSONObject(text).apply{getJSONArray("pump_profiles").getJSONObject(0).put("unknownField","new")},
            JSONObject(text).apply{getJSONArray("pump_profiles").getJSONObject(0).put("type","FUTURE")},
            JSONObject(text).apply{getJSONArray("sessions").getJSONObject(0).put("pumpProfileId","missing")},
            JSONObject(text).apply{getJSONArray("pump_profiles").getJSONObject(0).put("archived","false")},
        )
        invalid.forEach { root ->
            val other=memory()
            try{val r=Repository(other);val b=backup(r);val before=b.export();assertTrue(runCatching{b.import(root.toString())}.isFailure);assertEquals(before,b.export())}finally{other.close()}
        }
        val conflict=JSONObject(text).apply{getJSONArray("pump_profiles").getJSONObject(0).put("nickname","Different")}
        val before=backup(repo).export();assertTrue(runCatching{backup(repo).import(conflict.toString())}.isFailure);assertEquals(before,backup(repo).export())
        assertTrue(runCatching{LegacyBackup(repo,store(repo)).export()}.isFailure)
    }

    @Test fun modelSupportsCombinedProfileTypeContextAndDatePredicates()=runBlocking {
        val a=PumpProfile(nickname="Spectra",type=PumpType.STATIONARY);val b=PumpProfile(nickname="Momcozy",type=PumpType.MOBILE_OR_WEARABLE)
        repo.savePumpProfile(a);repo.savePumpProfile(b)
        repo.saveSession(session("a-no",a.id,status=BreastfeedingStatus.NO));repo.saveSession(session("a-yes",a.id,status=BreastfeedingStatus.YES));repo.saveSession(session("b-no",b.id,status=BreastfeedingStatus.NO))
        db.openHelper.readableDatabase.query("SELECT s.id FROM sessions s JOIN pump_profiles p ON p.id=s.pumpProfileId WHERE p.type='STATIONARY' AND s.breastfedBefore='NO' AND s.start>=1000 AND s.end<=61000").use{
            assertEquals(1,it.count);assertTrue(it.moveToFirst());assertEquals("a-no",it.getString(0))
        }
    }
    @Test fun profilesAndLastUsedSurviveDiskReopen()=runBlocking {
        val name="synthetic-pump-reopen-${System.nanoTime()}.db"
        fun open()=Room.databaseBuilder(context,PumpDatabase::class.java,name)
            .addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5).allowMainThreadQueries().build()
        var disk=open()
        val p=PumpProfile(nickname="Persistent pump",notes="Synthetic note")
        val s=session("persistent",p.id)
        try {
            Repository(disk).let{it.savePumpProfile(p);it.saveSession(s)}
            disk.close();disk=open()
            val r=Repository(disk);assertEquals(p,r.dao.pumpProfile(p.id));assertEquals(s,r.dao.session(s.id));assertEquals(p.id,r.defaultPumpProfile())
        }finally{disk.close();context.deleteDatabase(name)}
    }

}
