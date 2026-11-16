package app.pumping

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pumping.data.*
import app.pumping.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35],application=PumpingApp::class)
class PowerPersistenceTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun memory()=Room.inMemoryDatabaseBuilder(context,PumpDatabase::class.java).allowMainThreadQueries().build()
    private lateinit var db:PumpDatabase
    private lateinit var r:Repository
    private fun backup(repo:Repository)=Backup(repo,SettingsStore(context,repo.dao))
    private fun session(id:String="synthetic-power")=Session(id=id,start=1000,end=3601000,activeSeconds=2400,volume=100000,
        notes="Synthetic power note",breastfedBefore=BreastfeedingStatus.NO,powerStatus=PowerPumpStatus.POWER)
    @Before fun setup(){db=memory();r=Repository(db)}
    @After fun close(){db.close()}
    @Test fun ordinaryUnknownAndToggleOnlyModesStoreNoPhases()=runBlocking {
        val normal=Session(id="normal",start=1000,end=61000,activeSeconds=60,volume=1000,powerStatus=PowerPumpStatus.NORMAL)
        val unknown=normal.copy(id="historical",powerStatus=PowerPumpStatus.UNKNOWN)
        val toggle=normal.copy(id="toggle",powerStatus=PowerPumpStatus.POWER,elapsedKnown=false)
        listOf(normal,unknown,toggle).forEach{r.saveSession(it)}
        assertTrue(r.dao.allPhases().isEmpty());assertEquals(3,r.dao.allSessions().size)
        assertEquals(toggle,r.dao.session(toggle.id))
    }
    @Test fun phaseReplacementKeepsStableIdsAndValidationIsAtomic()=runBlocking {
        val s=session();val p=powerPreset(s.id);r.saveSession(s,listOf(Allocation(50000,"Fridge",4)),p)
        val before=backup(r).export()
        assertEquals(listOf(s.id),r.dao.detailedSessionIds().first())
        assertTrue(runCatching{r.saveSession(s.copy(notes="Invalid edit"),listOf(Allocation(1000,"Fridge",4)),p.map{it.copy(seconds=1)})}.isFailure)
        assertEquals(before,backup(r).export())
        val reordered=p.reversed().mapIndexed{i,x->x.copy(position=i)}
        r.saveSession(s,phases=reordered);assertEquals(reordered,r.dao.phases(s.id))
        r.saveSession(s.copy(notes="Valid note-only edit"));assertEquals(reordered,r.dao.phases(s.id))
        assertEquals(1,r.dao.allBags().size);assertEquals(100000,r.dao.allSessions().sumOf{it.volume})
        r.deleteBag(r.dao.allBags().single().id)
        assertEquals(reordered,r.dao.phases(s.id));assertEquals(100000,r.dao.session(s.id)!!.volume)
    }
    @Test fun deletingParentCleansPhasesButKeepsChosenStashAndNoResurrection()=runBlocking {
        val s=session();val p=powerPreset(s.id);r.saveSession(s,listOf(Allocation(50000,"Fridge",4)),p)
        val old=backup(r).export();val bag=r.dao.allBags().single();val moves=r.dao.allMovements()
        r.deleteSession(s.id)
        assertTrue(r.dao.allPhases().isEmpty());assertTrue(r.dao.detailedSessionIds().first().isEmpty());assertEquals(bag.copy(sourceSession=null),r.dao.bag(bag.id));assertEquals(moves,r.dao.allMovements())
        backup(r).import(old);assertNull(r.dao.session(s.id));assertTrue(r.dao.allPhases().isEmpty())
        assertEquals(0,JSONObject(backup(r).export()).getJSONArray("session_phases").length())
    }
    @Test fun nativeV4RoundTripPreservesAllEarlierDataAndIntervals()=runBlocking {
        val legacy=LegacyBackup(r,SettingsStore(context,r.dao))
        val text=javaClass.classLoader!!.getResourceAsStream("legacy/synthetic_fixture.pumping")!!.bufferedReader().use{it.readText()}
        legacy.import(legacy.prepare(text))
        val imported=r.dao.allSessions().first()
        r.saveSession(imported.copy(powerStatus=PowerPumpStatus.POWER))
        legacy.import(legacy.prepare(text))
        assertEquals(PowerPumpStatus.POWER,r.dao.session(imported.id)!!.powerStatus)
        val profile=PumpProfile(nickname="Power routine pump",notes="Synthetic profile");r.savePumpProfile(profile)
        val s=session().copy(pumpProfileId=profile.id);val p=powerPreset(s.id)
        r.saveSession(s,listOf(Allocation(60000,"Freezer",180)),p);r.archivePumpProfile(profile.id,true)
        val bag=r.dao.linkedBags(s.id).single();r.useBag(bag.id,10000,"Donation",bag.stored+1,"Synthetic use")
        r.saveSession(s.copy(id="deleted",pumpProfileId=null),phases=powerPreset("deleted"));r.deleteSession("deleted")
        val exported=backup(r).export();assertEquals(4,JSONObject(exported).getInt("version"))
        val other=memory()
        try {
            val repo=Repository(other);val b=backup(repo);b.import(exported);b.import(exported)
            assertEquals(r.dao.allSessions().toSet(),repo.dao.allSessions().toSet());assertEquals(r.dao.allPhases(),repo.dao.allPhases())
            assertEquals(r.dao.allPumpProfiles(),repo.dao.allPumpProfiles());assertEquals(r.dao.allBags(),repo.dao.allBags())
            assertEquals(r.dao.allMovements().toSet(),repo.dao.allMovements().toSet());assertEquals(r.dao.deletions(),repo.dao.deletions())
            assertEquals(r.dao.legacyRecords().toSet(),repo.dao.legacyRecords().toSet());assertEquals(r.dao.legacyMetadata().toSet(),repo.dao.legacyMetadata().toSet())
        }finally{other.close()}
    }
    @Test fun versionsOneTwoThreeRemainUnknownWithoutFabricatedPhases()=runBlocking {
        val p=PumpProfile(nickname="Old profile");r.savePumpProfile(p)
        val old=Session(id="old",start=1000,end=61000,activeSeconds=60,volume=1000,breastfedBefore=BreastfeedingStatus.YES,pumpProfileId=p.id)
        r.saveSession(old)
        for(v in 1..3){
            val root=JSONObject(backup(r).export()).put("version",v);root.remove("session_phases")
            val row=root.getJSONArray("sessions").getJSONObject(0);row.remove("powerStatus");row.remove("elapsedKnown")
            if(v<3){root.remove("pump_profiles");row.remove("pumpProfileId")};if(v==1)row.remove("breastfedBefore")
            val other=memory()
            try{val repo=Repository(other);backup(repo).import(root.toString());val saved=repo.dao.allSessions().single()
                assertEquals(PowerPumpStatus.UNKNOWN,saved.powerStatus);assertTrue(saved.elapsedKnown);assertTrue(repo.dao.allPhases().isEmpty())
                assertEquals(if(v>=3)p.id else null,saved.pumpProfileId)
                assertEquals(if(v>=2)BreastfeedingStatus.YES else BreastfeedingStatus.UNKNOWN,saved.breastfedBefore)
            }finally{other.close()}
        }
    }
    @Test fun invalidNativeIntervalsAndStalePhaseListsRollBack()=runBlocking {
        val s=session();val p=powerPreset(s.id);r.saveSession(s,phases=p);val text=backup(r).export()
        val malformed=listOf(
            JSONObject(text).put("version",5),
            JSONObject(text).apply{getJSONArray("session_phases").getJSONObject(0).put("sessionId","orphan")},
            JSONObject(text).apply{getJSONArray("session_phases").getJSONObject(0).put("position",99)},
            JSONObject(text).apply{getJSONArray("session_phases").getJSONObject(0).put("seconds",-1)},
            JSONObject(text).apply{getJSONArray("session_phases").getJSONObject(0).put("seconds",1.5)},
            JSONObject(text).apply{getJSONArray("sessions").getJSONObject(0).put("elapsedKnown",false)},
            JSONObject(text).apply{getJSONArray("sessions").getJSONObject(0).put("powerStatus","FUTURE")},
        )
        malformed.forEach{root->val other=memory();try{val repo=Repository(other);assertTrue(runCatching{backup(repo).import(root.toString())}.isFailure);assertTrue(repo.dao.allSessions().isEmpty());assertTrue(repo.dao.allPhases().isEmpty())}finally{other.close()}}
        val reversed=p.reversed().mapIndexed{i,x->x.copy(position=i)};r.saveSession(s,phases=reversed)
        val before=backup(r).export();assertTrue(runCatching{backup(r).import(text)}.isFailure);assertEquals(before,backup(r).export())
    }
    @Test fun pausedTimerPowerClassificationDoesNotChangeRecordedTime()=runBlocking {
        val timer=Session(id="paused",start=1000,end=3601321,activeSeconds=2400,volume=1000,mode="TIMER")
        r.saveSession(timer);r.saveSession(timer.copy(powerStatus=PowerPumpStatus.POWER))
        val marked=r.dao.session(timer.id)!!;assertEquals(timer.copy(powerStatus=PowerPumpStatus.POWER),marked)
        r.saveSession(marked,phases=powerPreset(marked.id));assertEquals(timer.start,r.dao.session(timer.id)!!.start);assertEquals(timer.end,r.dao.session(timer.id)!!.end)
        assertTrue(runCatching{r.saveSession(marked,phases=listOf(SessionPhase(sessionId=marked.id,position=0,kind=PhaseKind.PUMP,seconds=2400)))}.isFailure)
    }
    @Test fun metadataRefusesLossyLegacyExportAndSupportsCombinedPredicates()=runBlocking {
        val s=Session(id="normal",start=1000,end=61000,activeSeconds=60,volume=1000,powerStatus=PowerPumpStatus.NORMAL)
        r.saveSession(s)
        assertTrue(runCatching{LegacyBackup(r,SettingsStore(context,r.dao)).export()}.isFailure)
        r.saveSession(s.copy(id="power",powerStatus=PowerPumpStatus.POWER,elapsedKnown=false))
        db.openHelper.readableDatabase.query("SELECT COUNT(*),SUM(volume),SUM(activeSeconds) FROM sessions WHERE powerStatus='POWER' AND breastfedBefore='UNKNOWN' AND start>=1000").use{assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0));assertEquals(1000,it.getLong(1));assertEquals(60,it.getLong(2))}
    }
    @Test fun existingV4BasicTimingAndMillisecondPrecisionRoundTripUnchanged()=runBlocking {
        val nominal=Session(id="nominal-ms",start=1000,end=61321,activeSeconds=60,volume=1000,
            powerStatus=PowerPumpStatus.POWER,elapsedKnown=false)
        val previous=session("old-basic-known").copy(activeSeconds=1200)
        r.saveSession(nominal);r.saveSession(previous)
        val snapshot=backup(r).export()
        val other=memory()
        try {
            val repo=Repository(other);backup(repo).import(snapshot)
            assertEquals(nominal,repo.dao.session(nominal.id))
            assertEquals(previous,repo.dao.session(previous.id))
            assertNull(actualElapsedMillis(repo.dao.session(previous.id)!!))
            assertTrue(repo.dao.allPhases().isEmpty())
        } finally {other.close()}
    }

}
