package app.pumping

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pumping.data.*
import app.pumping.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = PumpingApp::class)
class RepositoryTest {
    private lateinit var db: PumpDatabase
    private lateinit var repo: Repository
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
    }

    @After
    fun close() {
        db.close()
    }

    private fun session(volume: Long = 9000) =
        Session(
            start = now - 1200000,
            end = now,
            activeSeconds = 1200,
            volume = volume,
            notes = "Synthetic\nmultiline note",
        )

    @Test
    fun sessionAndTwoBagsAreAtomic() = runBlocking {
        repo.saveSession(
            session(),
            listOf(Allocation(5000, "Fridge", 4), Allocation(4000, "Freezer", 180)),
        )
        assertEquals(1, repo.dao.allSessions().size)
        assertEquals(9000L, repo.dao.allSessions().sumOf { it.volume })
        assertEquals(9000L, repo.dao.allMovements().sumOf { it.delta })
        assertEquals(listOf(1, 2), repo.dao.allBags().map { it.number })
    }

    @Test
    fun allocationFailureRollsBackAllRecords() = runBlocking {
        assertTrue(
            runCatching {
                    repo.saveSession(
                        session(),
                        listOf(Allocation(1000, "Fridge", 4), Allocation(1000, "Invalid", 4)),
                    )
                }
                .isFailure
        )
        assertTrue(repo.dao.allSessions().isEmpty())
        assertTrue(repo.dao.allBags().isEmpty())
        assertTrue(repo.dao.allMovements().isEmpty())
    }

    @Test
    fun cannotOverallocate() = runBlocking {
        assertTrue(
            runCatching { repo.saveSession(session(1000), listOf(Allocation(1001, "Fridge", 4))) }
                .isFailure
        )
        assertTrue(repo.dao.allSessions().isEmpty())
    }

    @Test
    fun partialUseAndReasonPreserved() = runBlocking {
        repo.addBag(Allocation(5000, "Freezer", 180), now)
        val b = repo.dao.allBags().single()
        repo.useBag(b.id, 2000, "Feeding", now + 1, "Synthetic usage")
        assertEquals(3000L, repo.dao.remaining(b.id))
        assertEquals("Feeding", repo.dao.allMovements().first().kind)
        assertTrue(runCatching { repo.useBag(b.id, 3001, "Donation", now + 2, "") }.isFailure)
        assertEquals(2, repo.dao.allMovements().size)
    }

    @Test
    fun concurrentWithdrawalsCannotOverspend() = runBlocking {
        repo.addBag(Allocation(5000, "Fridge", 4), now)
        val b = repo.dao.allBags().single()
        val results =
            List(2) {
                    async(Dispatchers.IO) {
                        runCatching { repo.useBag(b.id, 4000, "Other", now + 1, "") }.isSuccess
                    }
                }
                .awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(1000L, repo.dao.remaining(b.id))
    }

    @Test
    fun deletePumpPreservesBagAndLedger() = runBlocking {
        val s = session()
        repo.saveSession(s, listOf(Allocation(9000, "Fridge", 4)))
        repo.deleteSession(s.id)
        assertTrue(repo.dao.allSessions().isEmpty())
        assertNull(repo.dao.allBags().single().sourceSession)
        assertEquals(9000L, repo.dao.allMovements().sumOf { it.delta })
    }

    @Test
    fun savedAllocationCannotBeSilentlyReduced() = runBlocking {
        val s = session()
        repo.saveSession(s, listOf(Allocation(9000, "Fridge", 4)))
        assertTrue(runCatching { repo.saveSession(s.copy(volume = 8000)) }.isFailure)
        assertEquals(9000L, repo.dao.allSessions().single().volume)
    }

    @Test
    fun transferDoesNotExtendExpiry() = runBlocking {
        repo.addBag(Allocation(1000, "Fridge", 4), now)
        val b = repo.dao.allBags().single()
        repo.transfer(b.id, "Freezer", 180, now + 1000)
        val updated = repo.dao.allBags().single()
        assertEquals(b.expires, updated.expires)
        assertEquals("Freezer", updated.location)
        assertEquals(1000L, repo.dao.remaining(b.id))
        assertTrue(repo.dao.allMovements().any { it.kind == "TRANSFER" && it.delta == 0L })
    }

    @Test
    fun backupRoundTripAndIdempotentImport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backup = Backup(repo, SettingsStore(context))
        repo.saveSession(session(), listOf(Allocation(9000, "Freezer", 180)))
        val b = repo.dao.allBags().single()
        repo.useBag(b.id, 1000, "Donation", now + 1, "Synthetic donation")
        val text = backup.export()
        val other =
            Room.inMemoryDatabaseBuilder(context, PumpDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        try {
            val imported = Backup(Repository(other), SettingsStore(context))
            imported.import(text)
            imported.import(text)
            assertEquals(repo.dao.allSessions(), other.dao().allSessions())
            assertEquals(repo.dao.allBags(), other.dao().allBags())
            assertEquals(repo.dao.allMovements(), other.dao().allMovements())
        } finally {
            other.close()
        }
    }

    @Test
    fun persistenceAfterDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-persistence-${System.nanoTime()}.db"
        var persistent =
            Room.databaseBuilder(context, PumpDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
        try {
            Repository(persistent).saveSession(session())
            persistent.close()
            persistent =
                Room.databaseBuilder(context, PumpDatabase::class.java, name)
                    .allowMainThreadQueries()
                    .build()
            assertEquals("Synthetic\nmultiline note", persistent.dao().allSessions().single().notes)
        } finally {
            persistent.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun settingsAndTimerPersist() = runBlocking {
        val store = SettingsStore(ApplicationProvider.getApplicationContext())
        store.string("unit", "mL")
        val t = TimerState("synthetic", now, 2500, 1000, 1, true, 20)
        store.saveTimer(t)
        assertEquals("mL", store.settings.first().displayUnit)
        assertEquals(t, store.timer.first())
    }

    @Test
    fun mergedBackupCannotOverdrawInventory() = runBlocking {
        repo.addBag(Allocation(5000, "Fridge", 4), now)
        val b = repo.dao.allBags().single()
        repo.useBag(b.id, 2000, "Feeding", now + 1, "Synthetic")
        val backup = Backup(repo, SettingsStore(ApplicationProvider.getApplicationContext()))
        val root = org.json.JSONObject(backup.export())
        val transactions = root.getJSONArray("movements")
        for (i in 0 until transactions.length()) {
            val m = transactions.getJSONObject(i)
            if (m.getString("kind") == "Feeding") {
                m.put("id", "synthetic-fork")
                m.put("delta", -4000)
            }
        }
        assertTrue(runCatching { backup.import(root.toString()) }.isFailure)
        assertEquals(3000L, repo.dao.remaining(b.id))
        assertEquals(2, repo.dao.allMovements().size)
    }

    @Test
    fun allUsageReasonsAndMonotonicBagNumbers() = runBlocking {
        repo.addBag(Allocation(4000, "Fridge", 4), now)
        val b = repo.dao.allBags().single()
        listOf("Feeding", "Donation", "Other", "Discard").forEachIndexed { i, reason ->
            repo.useBag(b.id, 1000, reason, now + i + 1, "Synthetic")
        }
        assertEquals(0L, repo.dao.remaining(b.id))
        repo.addBag(Allocation(1000, "Freezer", 180), now + 10)
        assertEquals(listOf(1, 2), repo.dao.allBags().map { it.number })
        assertEquals(
            setOf("ADD", "Feeding", "Donation", "Other", "Discard"),
            repo.dao.allMovements().map { it.kind }.toSet(),
        )
    }
}
