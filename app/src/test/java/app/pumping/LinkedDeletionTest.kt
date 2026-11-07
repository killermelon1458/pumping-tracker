package app.pumping

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
class LinkedDeletionTest {
    private lateinit var db: PumpDatabase
    private lateinit var repo: Repository
    private lateinit var store: SettingsStore
    private lateinit var legacy: LegacyBackup
    private val now = 1793887200000L

    @Before
    fun setup() {
        db =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    PumpDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
        repo = Repository(db)
        store = SettingsStore(ApplicationProvider.getApplicationContext(), db.dao())
        legacy = LegacyBackup(repo, store)
    }

    @After
    fun close() {
        db.close()
    }

    private fun pump(id: String = newId()) =
        Session(id, now - 1200000, now, 1200, 9000, notes = "Synthetic deletion test")

    private suspend fun independent(quantity: Long = 1000): Bag {
        repo.addBag(Allocation(quantity, "Fridge", 4), now)
        return repo.dao.allBags().maxBy { it.number }
    }

    private suspend fun linked(s: Session, count: Int = 1): List<Bag> {
        repo.saveSession(s, List(count) { Allocation(3000, "Freezer", 180) })
        return repo.dao.linkedBags(s.id)
    }

    private fun fixture() =
        javaClass.classLoader!!
            .getResourceAsStream("legacy/synthetic_fixture.pumping")!!
            .bufferedReader()
            .use { it.readText() }

    private suspend fun total() = repo.dao.allBags().sumOf { repo.dao.remaining(it.id) }

    @Test
    fun independentBagDeletionRemovesItsCompleteLedgerWithoutCreatingUsage() = runBlocking {
        val a = independent(5000)
        val b = independent(2000)
        repo.useBag(a.id, 1000, "Feeding", now + 1, "Synthetic")
        val other = repo.dao.allMovements().filter { it.bagId == b.id }
        repo.deleteBag(a.id)
        assertNull(repo.dao.bag(a.id))
        assertEquals(other, repo.dao.allMovements())
        assertEquals(2000L, total())
        assertTrue(repo.dao.allSessions().isEmpty())
        assertTrue(repo.dao.isDeleted("bag", a.id))
        assertEquals(2, repo.dao.deletions().count { it.kind == "movement" })
    }

    @Test
    fun deletePumpWithOneLinkedBagAndKeepUnrelatedRecords() = runBlocking {
        val s = pump()
        val b = linked(s).single()
        val other = independent()
        repo.deleteSession(s.id, true)
        assertNull(repo.dao.session(s.id))
        assertNull(repo.dao.bag(b.id))
        assertEquals(listOf(other), repo.dao.allBags())
        assertEquals(1, repo.dao.allMovements().size)
    }

    @Test
    fun deletePumpWithMultipleBagsIsAtomicAndExplicit() = runBlocking {
        val s = pump()
        val bags = linked(s, 2)
        val other = pump()
        val unrelated = linked(other).single()
        val plan = repo.deletionPlan(DeleteTarget.SESSION, s.id)
        assertEquals(bags, plan.bags)
        assertEquals(6000L, plan.bags.sumOf { it.initial })
        repo.delete(plan, true)
        assertEquals(listOf(other), repo.dao.allSessions())
        assertEquals(listOf(unrelated), repo.dao.allBags())
        assertEquals(3000L, total())
        assertEquals(1, repo.dao.allMovements().size)
    }

    @Test
    fun keepSessionWhileDeletingItsBag() = runBlocking {
        val s = pump()
        val bags = linked(s, 2)
        repo.deleteBag(bags.first().id)
        assertEquals(s, repo.dao.session(s.id))
        assertEquals(s.id, repo.dao.bag(bags.last().id)!!.sourceSession)
        assertEquals(9000L, totals(repo.dao.allSessions()).quantity)
        assertEquals(3000L, total())
    }

    @Test
    fun keepBagsWhileDeletingPump() = runBlocking {
        val s = pump()
        val bags = linked(s, 2)
        val ledger = repo.dao.allMovements()
        repo.deleteSession(s.id)
        assertNull(repo.dao.session(s.id))
        assertEquals(ledger, repo.dao.allMovements())
        assertEquals(bags.map { it.id }, repo.dao.allBags().map { it.id })
        assertTrue(repo.dao.allBags().all { it.sourceSession == null })
        assertEquals(6000L, total())
    }

    @Test
    fun deletingBagAndOriginatingPumpKeepsSiblingBagsDetached() = runBlocking {
        val s = pump()
        val bags = linked(s, 2)
        val other = pump()
        val unrelated = linked(other).single()
        val plan = repo.deletionPlan(DeleteTarget.BAG, bags.first().id)
        assertEquals(1, plan.deletedBags(true).size)
        assertEquals(s, plan.deletedSession(true))
        repo.delete(plan, true)
        assertNull(repo.dao.session(s.id))
        assertNull(repo.dao.bag(bags.first().id))
        assertNull(repo.dao.bag(bags.last().id)!!.sourceSession)
        assertEquals(other.id, repo.dao.bag(unrelated.id)!!.sourceSession)
        assertEquals(6000L, total())
    }

    @Test
    fun deletionRemovesAddedUsedAndFeedingSavingsTotalsRatherThanAddingDiscard() = runBlocking {
        val b = independent(5000)
        repo.useBag(b.id, 2000, "Feeding", now + 1, "")
        repo.useBag(b.id, 1000, "Discard", now + 2, "")
        assertEquals(2000L, total())
        repo.deleteBag(b.id)
        assertEquals(0L, total())
        assertTrue(repo.dao.allMovements().isEmpty())
        assertEquals(0L, repo.dao.allMovements().sumOf { it.delta })
    }

    @Test
    fun deletingEveryBagResetsNextNumberToOne() = runBlocking {
        val bags = List(3) { independent() }
        bags.forEach { repo.deleteBag(it.id) }
        assertEquals(1, repo.dao.nextNumber())
        assertEquals(1, independent().number)
    }

    @Test
    fun deletingMiddleBagDoesNotRenumberRetainedBags() = runBlocking {
        val bags = List(3) { independent() }
        repo.deleteBag(bags[1].id)
        assertEquals(listOf(1, 3), repo.dao.allBags().map { it.number })
        assertEquals(4, independent().number)
        assertEquals(bags[0], repo.dao.bag(bags[0].id))
        assertEquals(bags[2], repo.dao.bag(bags[2].id))
    }

    @Test
    fun highestNumberCanBeReusedButExhaustedRetainedBagsReserveTheirNumbers() = runBlocking {
        val bags = List(3) { independent() }
        repo.useBag(bags.last().id, 1000, "Discard", now + 1, "")
        assertEquals(4, repo.dao.nextNumber())
        repo.deleteBag(bags.last().id)
        assertEquals(3, independent().number)
        assertEquals(listOf(1, 2, 3), repo.dao.allBags().map { it.number })
    }

    @Test
    fun reusedNumberNeverTransfersStableSessionOwnership() = runBlocking {
        val a = pump()
        val old = linked(a).single()
        repo.deleteBag(old.id)
        val b = pump()
        val current = linked(b).single()
        assertEquals(old.number, current.number)
        assertNotEquals(old.id, current.id)
        repo.deleteSession(a.id, true)
        assertEquals(b.id, repo.dao.bag(current.id)!!.sourceSession)
        assertEquals(b, repo.dao.session(b.id))
    }

    @Test
    fun changedLinksOrTransactionsRequireANewConfirmation() = runBlocking {
        val s = pump()
        val bag = linked(s).single()
        val plan = repo.deletionPlan(DeleteTarget.SESSION, s.id)
        repo.useBag(bag.id, 1000, "Other", now + 1, "")
        assertTrue(runCatching { repo.delete(plan, true) }.isFailure)
        assertNotNull(repo.dao.session(s.id))
        assertNotNull(repo.dao.bag(bag.id))
    }

    @Test
    fun sqliteFailureRollsBackEveryDeletionAndMarker() = runBlocking {
        val s = pump()
        linked(s, 2)
        val before = Backup(repo, store).export()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER synthetic_delete_failure BEFORE DELETE ON sessions BEGIN SELECT RAISE(ABORT,'Synthetic deletion failure'); END"
        )
        assertTrue(runCatching { repo.deleteSession(s.id, true) }.isFailure)
        assertEquals(before, Backup(repo, store).export())
        assertTrue(repo.dao.deletions().isEmpty())
    }

    @Test
    fun deletedImportedBagDoesNotReturnOrReserveItsOldNumberOnMerge() = runBlocking {
        val p = legacy.prepare(fixture())
        legacy.import(p)
        val bag = repo.dao.allBags().single()
        val source = repo.dao.session(bag.sourceSession!!)!!
        repo.deleteBag(bag.id)
        assertEquals(1, repo.dao.nextNumber())
        assertEquals(3, repo.dao.allSessions().size)
        val preview = legacy.preview(p)
        assertEquals(0, preview.conflicts)
        assertEquals(1, preview.deletedStash)
        legacy.import(p)
        legacy.import(p)
        assertTrue(repo.dao.allBags().isEmpty())
        assertTrue(repo.dao.allMovements().isEmpty())
        assertEquals(1, repo.dao.nextNumber())
        assertEquals(source, repo.dao.session(source.id))
    }

    @Test
    fun importedBagDeletionClearsRawAllocationsEvenAfterNumberReuse() = runBlocking {
        legacy.import(legacy.prepare(fixture()))
        val bag = repo.dao.allBags().single()
        val sourceId = bag.sourceSession!!
        repo.deleteBag(bag.id)
        val replacement = independent(2000)
        assertEquals(1, replacement.number)
        legacy.import(legacy.prepare(fixture()))
        val doc = LegacyFormat.decode(legacy.export().text)
        val pump =
            LegacyFormat.records(doc, "pump_records").single { it.getString("id") == sourceId }
        assertTrue(pump.isNull("stashBagId"))
        assertTrue(pump.isNull("stashBagIds"))
        assertEquals(0.0, pump.getDouble("stashAmount"), 0.0)
        val stash = LegacyFormat.records(doc, "stash_records")
        assertEquals(1, stash.size)
        assertNotEquals("1", stash.single().getString("id"))
        assertEquals(2000L, total())
        val native = JSONObject(Backup(repo, store).export())
        assertEquals(1, native.getJSONArray("bags").length())
        val raw = native.getJSONArray("legacy_records")
        for (i in 0 until raw.length()) {
            val r = raw.getJSONObject(i)
            assertFalse(r.getString("kind") == "stash" && r.getString("id") == "1")
        }
    }

    @Test
    fun importedSessionDeletionKeepsBagWithoutOrphanReferencesAndMergeDoesNotRestorePump() =
        runBlocking {
            val p = legacy.prepare(fixture())
            legacy.import(p)
            val b = repo.dao.allBags().single()
            val source = b.sourceSession!!
            repo.deleteSession(source)
            legacy.import(p)
            assertEquals(2, repo.dao.allSessions().size)
            assertNull(repo.dao.session(source))
            assertNull(repo.dao.bag(b.id)!!.sourceSession)
            val doc = LegacyFormat.decode(legacy.export().text)
            assertFalse(
                LegacyFormat.records(doc, "pump_records").any { it.getString("id") == source }
            )
            assertEquals(1, LegacyFormat.records(doc, "stash_records").size)
            val raw = repo.dao.legacyRecords().single { it.kind == "stash" }
            assertTrue(JSONObject(raw.projection).isNull("sourceSession"))
        }

    @Test
    fun confirmedFullReplacementRestoresDeletedImportedRecords() = runBlocking {
        val p = legacy.prepare(fixture())
        legacy.import(p)
        val source = repo.dao.allBags().single().sourceSession!!
        repo.deleteSession(source, true)
        legacy.import(p)
        assertEquals(2, repo.dao.allSessions().size)
        assertTrue(repo.dao.allBags().isEmpty())
        assertTrue(runCatching { legacy.import(p, LegacyImportMode.REPLACE) }.isFailure)
        legacy.import(p, LegacyImportMode.REPLACE, replaceConfirmed = true)
        assertEquals(3, repo.dao.allSessions().size)
        assertEquals(1, repo.dao.allBags().size)
        assertTrue(repo.dao.deletions().isEmpty())
    }

    @Test
    fun nativeBackupsCarryIdOnlyDeletionMarkersAndCannotResurrectFromOlderFiles() = runBlocking {
        val p = legacy.prepare(fixture())
        legacy.import(p)
        val original = Backup(repo, store).export()
        val b = repo.dao.allBags().single()
        repo.deleteBag(b.id)
        Backup(repo, store).import(original)
        assertTrue(repo.dao.allBags().isEmpty())
        assertEquals(1, repo.dao.nextNumber())
        val after = Backup(repo, store).export()
        val other =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    PumpDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
        try {
            val r = Repository(other)
            val s = SettingsStore(ApplicationProvider.getApplicationContext(), other.dao())
            Backup(r, s).import(after)
            val l = LegacyBackup(r, s)
            l.import(l.prepare(fixture()))
            assertTrue(r.dao.allBags().isEmpty())
            assertEquals(3, r.dao.allSessions().size)
            assertEquals(1, r.dao.nextNumber())
            val d = JSONObject(after).getJSONArray("deletions")
            for (i in 0 until d.length()) assertEquals(2, d.getJSONObject(i).length())
        } finally {
            other.close()
        }
    }

    @Test
    fun twoImportedBagsReleaseOnlyDeletedNumbersAndNeverRenumberSurvivors() = runBlocking {
        val doc = LegacyFormat.decode(fixture())
        val pumps = doc.getJSONObject("data").getJSONArray("pump_records")
        pumps.getJSONObject(0).put("stashBagId", 2).put("stashAmount", 29.5735)
        val stash = doc.getJSONObject("data").getJSONArray("stash_records")
        stash.put(
            JSONObject(stash.getJSONObject(0).toString())
                .put("id", "synthetic-second-bag")
                .put("bagId", 2)
        )
        val p = legacy.prepare(LegacyFormat.encode(doc))
        legacy.import(p)
        val bags = repo.dao.allBags()
        repo.deleteBag(bags.last().id)
        legacy.import(p)
        assertEquals(listOf(bags.first()), repo.dao.allBags())
        assertEquals(2, repo.dao.nextNumber())
        val fresh = independent()
        assertEquals(2, fresh.number)
        assertNotEquals(bags.last().id, fresh.id)
        repo.deleteBag(bags.first().id)
        repo.deleteBag(fresh.id)
        legacy.import(p)
        assertTrue(repo.dao.allBags().isEmpty())
        assertEquals(1, repo.dao.nextNumber())
    }

    @Test
    fun orphanedLegacyScalarReferencesAreNotExportedOrReboundByNumber() = runBlocking {
        val doc = LegacyFormat.decode(fixture())
        doc.getJSONObject("data")
            .getJSONArray("pump_records")
            .getJSONObject(1)
            .put("stashBagId", 999)
        val p = legacy.prepare(LegacyFormat.encode(doc))
        legacy.import(p)
        val saved = repo.dao.allSessions()
        val exported =
            LegacyFormat.records(LegacyFormat.decode(legacy.export().text), "pump_records").single {
                it.getString("id") == "2222222222222222"
            }
        assertTrue(exported.isNull("stashBagId"))
        assertEquals(0.0, exported.getDouble("stashAmount"), 0.0)
        legacy.import(p)
        assertEquals(saved, repo.dao.allSessions())
        assertEquals(1, repo.dao.allBags().size)
    }

    @Test
    fun olderNativeBackupImplicitPumpDeletionIsPreservedAndNotExportedAsRawData() = runBlocking {
        legacy.import(legacy.prepare(fixture()))
        val backup = JSONObject(Backup(repo, store).export())
        val rows = backup.getJSONArray("sessions")
        val kept = org.json.JSONArray()
        for (i in 0 until rows.length()) if (
            rows.getJSONObject(i).getString("id") != "1111111111111111"
        )
            kept.put(rows.getJSONObject(i))
        backup.put("sessions", kept)
        backup.remove("deletions")
        val other =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    PumpDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
        try {
            val r = Repository(other)
            val s = SettingsStore(ApplicationProvider.getApplicationContext(), other.dao())
            Backup(r, s).import(backup.toString())
            assertTrue(r.dao.isDeleted("session", "1111111111111111"))
            val l = LegacyBackup(r, s)
            l.import(l.prepare(fixture()))
            assertEquals(2, r.dao.allSessions().size)
            assertFalse(
                r.dao.legacyRecords().any { it.kind == "pump" && it.id == "1111111111111111" }
            )
            val exported = JSONObject(Backup(r, s).export()).getJSONArray("legacy_records")
            for (i in 0 until exported.length()) assertFalse(
                exported.getJSONObject(i).getString("kind") == "pump" &&
                    exported.getJSONObject(i).getString("id") == "1111111111111111"
            )
        } finally {
            other.close()
        }
    }
}
