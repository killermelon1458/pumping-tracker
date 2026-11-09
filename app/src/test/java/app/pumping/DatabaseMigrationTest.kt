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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
    @Test
    fun schemaThreeAddsUnknownAndPreservesAllTablesAndExplicitContextAfterReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-v3-migration-${System.nanoTime()}.db"
        val schema = JSONObject(javaClass.classLoader!!.getResourceAsStream("legacy/schema-v3.json")!!
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        val tables = mutableListOf<String>()
        try {
            val entities=schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e=entities.getJSONObject(i);val table=e.getString("tableName");tables+=table
                fun sql(text:String)=text.replace("\${TABLE_NAME}",table)
                old.execSQL(sql(e.getString("createSql")))
                val indices=e.optJSONArray("indices")
                if(indices!=null) for(n in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(n).getString("createSql")))
            }
            val setup=schema.getJSONArray("setupQueries")
            for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO sessions VALUES ('old',1000,61000,60,10000,NULL,NULL,'Synthetic old note','MANUAL',61000,61000)")
            old.execSQL("INSERT INTO bags VALUES ('bag',7,61000,5000,'Fridge',345661000,'Synthetic bag','old')")
            old.execSQL("INSERT INTO movements VALUES ('add','bag',61000,'ADD',5000,'',NULL,NULL)")
            old.execSQL("INSERT INTO movements VALUES ('use','bag',62000,'Feeding',-1000,'Synthetic use',NULL,NULL)")
            old.execSQL("INSERT INTO legacy_records VALUES ('pump','old','{\"id\":\"old\"}','{}','UTC')")
            old.execSQL("INSERT INTO legacy_metadata VALUES ('bag-number:8','8')")
            old.execSQL("INSERT INTO legacy_preferences VALUES (1,'synthetic-token','{\"inputUnit\":\"mL\"}')")
            old.execSQL("INSERT INTO deletions VALUES ('session','deleted-synthetic')")
            old.version=3
        } finally { old.close() }
        fun open()=Room.databaseBuilder(context,PumpDatabase::class.java,name)
            .addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5).allowMainThreadQueries().build()
        var upgraded=open()
        try {
            val dao=upgraded.dao();val s=dao.session("old")!!
            assertEquals(BreastfeedingStatus.UNKNOWN,s.breastfedBefore)
            assertEquals(1000L,s.start);assertEquals(61000L,s.end);assertEquals(60L,s.activeSeconds)
            assertEquals(10000L,s.volume);assertEquals("Synthetic old note",s.notes)
            assertEquals("old",dao.bag("bag")!!.sourceSession);assertEquals(4000L,dao.remaining("bag"))
            assertEquals(2,dao.allMovements().size);assertEquals("Synthetic use",dao.allMovements().first().notes)
            assertEquals("{\"id\":\"old\"}",dao.legacyRecords().single().json)
            assertEquals("8",dao.legacyMetadata().single().json)
            assertEquals("synthetic-token",dao.currentLegacyPreferences()!!.token)
            assertTrue(dao.isDeleted("session","deleted-synthetic"))
            dao.updateSession(s.copy(breastfedBefore=BreastfeedingStatus.YES))
            upgraded.close();upgraded=open()
            assertEquals(BreastfeedingStatus.YES,upgraded.dao().session("old")!!.breastfedBefore)
            assertEquals(4000L,upgraded.dao().remaining("bag"))
        } finally { upgraded.close();context.deleteDatabase(name) }
    }

    @Test
    fun schemaThreeRetainsCompleteIosFixtureAndDeletionHistory() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="synthetic-v3-ios-${System.nanoTime()}.db"
        val source=Room.inMemoryDatabaseBuilder(context,PumpDatabase::class.java).allowMainThreadQueries().build()
        val fixture=javaClass.classLoader!!.getResourceAsStream("legacy/synthetic_fixture.pumping")!!.bufferedReader().use { it.readText() }
        try {
            val original=Repository(source)
            val importer=LegacyBackup(original,SettingsStore(context,original.dao))
            importer.import(importer.prepare(fixture))
            val removed=original.dao.allSessions().first().id
            original.deleteSession(removed)
            val schema=JSONObject(javaClass.classLoader!!.getResourceAsStream("legacy/schema-v3.json")!!.bufferedReader().use { it.readText() }).getJSONObject("database")
            val old=context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null)
            try {
                val entities=schema.getJSONArray("entities")
                for(i in 0 until entities.length()) {
                    val e=entities.getJSONObject(i);val table=e.getString("tableName")
                    fun sql(text:String)=text.replace("\${TABLE_NAME}",table)
                    old.execSQL(sql(e.getString("createSql")))
                    val indices=e.optJSONArray("indices")
                    if(indices!=null) for(n in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(n).getString("createSql")))
                    source.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
                        val columns=cursor.columnNames.filter { it!="breastfedBefore" && it!="pumpProfileId" }
                        while(cursor.moveToNext()) {
                            val values=columns.map<String, Any?> { c ->
                                val index=cursor.getColumnIndexOrThrow(c)
                                when(cursor.getType(index)) {
                                    android.database.Cursor.FIELD_TYPE_NULL -> null
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                    else -> cursor.getString(index)
                                }
                            }.toTypedArray()
                            old.execSQL("INSERT INTO $table (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})",values)
                        }
                    }
                }
                val setup=schema.getJSONArray("setupQueries")
                for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.version=3
            } finally { old.close() }
            val upgraded=Room.databaseBuilder(context,PumpDatabase::class.java,name)
                .addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5).allowMainThreadQueries().build()
            try {
                val r=Repository(upgraded)
                assertEquals(original.dao.allSessions().toSet(),r.dao.allSessions().toSet())
                assertEquals(original.dao.allBags().toSet(),r.dao.allBags().toSet())
                assertEquals(original.dao.allMovements().toSet(),r.dao.allMovements().toSet())
                assertEquals(original.dao.legacyRecords().toSet(),r.dao.legacyRecords().toSet())
                assertEquals(original.dao.legacyMetadata().toSet(),r.dao.legacyMetadata().toSet())
                assertEquals(original.dao.deletions(),r.dao.deletions())
                LegacyBackup(r,SettingsStore(context,r.dao)).let { it.import(it.prepare(fixture)) }
                assertNull(r.dao.session(removed))
                assertTrue(r.dao.allSessions().all { it.breastfedBefore==BreastfeedingStatus.UNKNOWN })
            } finally { upgraded.close() }
        } finally { source.close();context.deleteDatabase(name) }
    }

    @Test
    fun schemaFourAddsNullableIndexedPumpRelationshipWithoutLosingRecords() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="synthetic-v4-pumps-${System.nanoTime()}.db"
        val source=Room.inMemoryDatabaseBuilder(context,PumpDatabase::class.java).allowMainThreadQueries().build()
        val fixture=javaClass.classLoader!!.getResourceAsStream("legacy/synthetic_fixture.pumping")!!.bufferedReader().use { it.readText() }
        try {
            val original=Repository(source)
            val importer=LegacyBackup(original,SettingsStore(context,original.dao))
            importer.import(importer.prepare(fixture))
            val initial=original.dao.allSessions()
            val withContext=initial.first().copy(breastfedBefore=BreastfeedingStatus.YES)
            original.saveSession(withContext)
            val removed=initial.last().id
            original.deleteSession(removed)
            val schema=JSONObject(javaClass.classLoader!!.getResourceAsStream("legacy/schema-v4.json")!!.bufferedReader().use { it.readText() }).getJSONObject("database")
            val old=context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null)
            try {
                val entities=schema.getJSONArray("entities")
                for(i in 0 until entities.length()) {
                    val e=entities.getJSONObject(i);val table=e.getString("tableName")
                    fun sql(text:String)=text.replace("\${TABLE_NAME}",table)
                    old.execSQL(sql(e.getString("createSql")))
                    val indices=e.optJSONArray("indices")
                    if(indices!=null) for(n in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(n).getString("createSql")))
                    source.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
                        val columns=cursor.columnNames.filter { it!="pumpProfileId" }
                        while(cursor.moveToNext()) {
                            val values=columns.map<String, Any?> { c ->
                                val index=cursor.getColumnIndexOrThrow(c)
                                when(cursor.getType(index)) {
                                    android.database.Cursor.FIELD_TYPE_NULL -> null
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                    else -> cursor.getString(index)
                                }
                            }.toTypedArray()
                            old.execSQL("INSERT INTO $table (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})",values)
                        }
                    }
                }
                val setup=schema.getJSONArray("setupQueries")
                for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.version=4
            } finally { old.close() }
            val upgraded=Room.databaseBuilder(context,PumpDatabase::class.java,name)
                .addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5).allowMainThreadQueries().build()
            try {
                val r=Repository(upgraded)
                assertEquals(original.dao.allSessions().toSet(),r.dao.allSessions().toSet())
                assertEquals(original.dao.allBags().toSet(),r.dao.allBags().toSet())
                assertEquals(original.dao.allMovements().toSet(),r.dao.allMovements().toSet())
                assertEquals(original.dao.legacyRecords().toSet(),r.dao.legacyRecords().toSet())
                assertEquals(original.dao.legacyMetadata().toSet(),r.dao.legacyMetadata().toSet())
                assertEquals(original.dao.deletions(),r.dao.deletions())
                LegacyBackup(r,SettingsStore(context,r.dao)).let { it.import(it.prepare(fixture)) }
                assertNull(r.dao.session(removed))
                assertEquals(BreastfeedingStatus.YES,r.dao.session(withContext.id)!!.breastfedBefore)
                assertTrue(r.dao.allSessions().all { it.pumpProfileId==null })
                assertTrue(r.dao.allPumpProfiles().isEmpty())
                val pump=PumpProfile(nickname="Migrated fixture pump")
                r.savePumpProfile(pump)
                val before=r.dao.session(withContext.id)!!
                r.saveSession(before.copy(pumpProfileId=pump.id))
                assertEquals(before.copy(pumpProfileId=pump.id),r.dao.session(before.id))
                upgraded.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0,it.count) }
                upgraded.openHelper.readableDatabase.query("PRAGMA index_list(sessions)").use { c ->
                    val names=mutableSetOf<String>()
                    while(c.moveToNext()) names+=c.getString(c.getColumnIndexOrThrow("name"))
                    assertTrue("index_sessions_pumpProfileId" in names)
                }
            } finally { upgraded.close() }
        } finally { source.close();context.deleteDatabase(name) }
    }

}
