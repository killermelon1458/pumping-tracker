package app.pumping

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pumping.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = PumpingApp::class)
class DatabaseMigrationTest {
    @Test
    fun schemaOneUpgradesWithoutChangingExistingData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-migration-${System.nanoTime()}.db"
        val schema =
            JSONObject(
                    javaClass.classLoader!!
                        .getResourceAsStream("legacy/schema-v1.json")!!
                        .bufferedReader()
                        .use { it.readText() }
                )
                .getJSONObject("database")
        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                fun sql(s: String) = s.replace("\${TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices")
                if (indices != null)
                    for (n in 0 until indices.length()) old.execSQL(
                        sql(indices.getJSONObject(n).getString("createSql"))
                    )
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL(
                "INSERT INTO sessions VALUES ('synthetic-existing',1000,61000,60,10000,NULL,NULL,'Synthetic old note','MANUAL',61000,61000)"
            )
            old.execSQL(
                "INSERT INTO bags VALUES ('synthetic-bag',7,61000,5000,'Fridge',345661000,'Synthetic bag','synthetic-existing')"
            )
            old.execSQL(
                "INSERT INTO movements VALUES ('synthetic-add','synthetic-bag',61000,'ADD',5000,'',NULL,NULL)"
            )
            old.version = 1
        } finally {
            old.close()
        }
        val upgraded =
            Room.databaseBuilder(context, PumpDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .allowMainThreadQueries()
                .build()
        try {
            val r = Repository(upgraded)
            assertEquals("Synthetic old note", r.dao.allSessions().single().notes)
            assertEquals(10000L, r.dao.allSessions().single().volume)
            assertEquals(7, r.dao.allBags().single().number)
            assertEquals("synthetic-existing", r.dao.allBags().single().sourceSession)
            assertEquals(5000L, r.dao.remaining("synthetic-bag"))
            assertTrue(r.dao.legacyRecords().isEmpty())
            val s = SettingsStore(context, r.dao)
            val legacy = LegacyBackup(r, s)
            val text =
                javaClass.classLoader!!
                    .getResourceAsStream("legacy/synthetic_fixture.pumping")!!
                    .bufferedReader()
                    .use { it.readText() }
            legacy.import(legacy.prepare(text))
            assertEquals(4, r.dao.allSessions().size)
            assertEquals(2, r.dao.allBags().size)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun schemaTwoPreservesImportedDataAndConvertsOldImplicitDeletions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-v2-migration-${System.nanoTime()}.db"
        val schema =
            JSONObject(
                    javaClass.classLoader!!
                        .getResourceAsStream("legacy/schema-v2.json")!!
                        .bufferedReader()
                        .use { it.readText() }
                )
                .getJSONObject("database")
        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        val fixture =
            javaClass.classLoader!!
                .getResourceAsStream("legacy/synthetic_fixture.pumping")!!
                .bufferedReader()
                .use { it.readText() }
        val records = LegacyFormat.records(LegacyFormat.decode(fixture), "pump_records")
        val temporary =
            Room.inMemoryDatabaseBuilder(context, PumpDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val plan =
            try {
                LegacyBackup(Repository(temporary), SettingsStore(context))
                    .prepare(fixture, java.time.ZoneId.of("UTC"))
            } finally {
                temporary.close()
            }

        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                fun sql(value: String) = value.replace("\${TABLE_NAME}", e.getString("tableName"))
                old.execSQL(sql(e.getString("createSql")))
                val indices = e.optJSONArray("indices")
                if (indices != null)
                    for (n in 0 until indices.length()) old.execSQL(
                        sql(indices.getJSONObject(n).getString("createSql"))
                    )
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL(
                "INSERT INTO sessions VALUES ('synthetic-existing',1000,61000,60,10000,NULL,NULL,'Synthetic v2 note','MANUAL',61000,61000)"
            )
            old.execSQL(
                "INSERT INTO bags VALUES ('synthetic-bag',7,61000,5000,'Fridge',345661000,'Synthetic bag','synthetic-existing')"
            )
            old.execSQL(
                "INSERT INTO movements VALUES ('synthetic-add','synthetic-bag',61000,'ADD',5000,'',NULL,NULL)"
            )
            plan.sessions
                .filter { it.id != records[0].getString("id") }
                .forEach { r ->
                    old.execSQL(
                        "INSERT INTO sessions VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>(
                            r.id,
                            r.start,
                            r.end,
                            r.activeSeconds,
                            r.volume,
                            r.left,
                            r.right,
                            r.notes,
                            r.mode,
                            r.created,
                            r.updated,
                        ),
                    )
                }
            plan.bags.forEach { r ->
                old.execSQL(
                    "INSERT INTO bags VALUES (?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(
                        r.id,
                        r.number,
                        r.stored,
                        r.initial,
                        r.location,
                        r.expires,
                        r.notes,
                        r.sourceSession,
                    ),
                )
            }
            plan.movements.forEach { r ->
                old.execSQL(
                    "INSERT INTO movements VALUES (?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(
                        r.id,
                        r.bagId,
                        r.time,
                        r.kind,
                        r.delta,
                        r.notes,
                        r.oldLocation,
                        r.newLocation,
                    ),
                )
            }
            plan.records.forEach { r ->
                old.execSQL(
                    "INSERT INTO legacy_records VALUES (?,?,?,?,?)",
                    arrayOf<Any?>(r.kind, r.id, r.json, r.projection, r.zone),
                )
            }
            old.version = 2
        } finally {
            old.close()
        }
        val upgraded =
            Room.databaseBuilder(context, PumpDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .allowMainThreadQueries()
                .build()
        try {
            val repo = Repository(upgraded)
            val dao = repo.dao
            assertEquals("Synthetic v2 note", dao.session("synthetic-existing")!!.notes)
            assertEquals(7, dao.bag("synthetic-bag")!!.number)
            assertEquals("synthetic-existing", dao.bag("synthetic-bag")!!.sourceSession)
            plan.sessions
                .filter { it.id != records[0].getString("id") }
                .forEach { assertEquals(it, dao.session(it.id)) }
            plan.bags.forEach { assertEquals(it, dao.bag(it.id)) }
            plan.movements.forEach { r -> assertTrue(dao.allMovements().contains(r)) }
            assertEquals(5000L, dao.remaining("synthetic-bag"))
            assertTrue(dao.isDeleted("session", records[0].getString("id")))
            assertEquals(
                plan.records
                    .filterNot { it.kind == "pump" && it.id == records[0].getString("id") }
                    .toSet(),
                dao.legacyRecords().toSet(),
            )
            val importer = LegacyBackup(repo, SettingsStore(context, dao))
            importer.import(importer.prepare(fixture, java.time.ZoneId.of("UTC")))
            assertNull(dao.session(records[0].getString("id")))
            assertEquals(3, dao.allSessions().size)
            assertEquals(2, dao.allBags().size)
            assertEquals(8, dao.nextNumber())
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
