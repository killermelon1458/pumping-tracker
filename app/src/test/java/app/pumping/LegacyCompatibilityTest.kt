package app.pumping

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pumping.data.*
import app.pumping.domain.*
import java.time.*
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = PumpingApp::class)
class LegacyCompatibilityTest {
    private lateinit var db: PumpDatabase
    private lateinit var repo: Repository
    private lateinit var store: SettingsStore
    private lateinit var legacy: LegacyBackup
    private val zone = ZoneId.of("America/Chicago")

    private fun resource(name: String) =
        javaClass.classLoader!!.getResourceAsStream("legacy/$name")!!.bufferedReader().use {
            it.readText()
        }

    private fun root() = JSONObject(resource("synthetic_fixture.json"))

    private fun fixture() = resource("synthetic_fixture.pumping")

    private fun modified(block: (JSONObject) -> Unit): String =
        root().also(block).let(LegacyFormat::encode)

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

    @Test
    fun iosSingleUnitRemainsCompatibleWithIndependentAndroidUnits() = runBlocking {
        val plan = legacy.prepare(fixture(), zone)
        legacy.import(plan, applySettings = true)
        val sessions = repo.dao.allSessions()
        val bags = repo.dao.allBags()
        store.string("inputUnit", "mL")
        assertEquals("oz", store.settings.first().displayUnit)
        val first = LegacyFormat.decode(legacy.export().text)
        assertEquals("oz", first.getJSONObject("settings").getString("volume_unit"))
        store.string("displayUnit", "mL")
        store.string("inputUnit", "oz")
        val second = LegacyFormat.decode(legacy.export().text)
        assertEquals("mL", second.getJSONObject("settings").getString("volume_unit"))
        assertTrue(
            LegacyFormat.equivalent(
                first.getJSONObject("data").toString(),
                second.getJSONObject("data").toString(),
            )
        )
        assertEquals(sessions, repo.dao.allSessions())
        assertEquals(bags, repo.dao.allBags())
        legacy.import(plan, applySettings = true)
        assertEquals("oz", store.settings.first().inputUnit)
        assertEquals("oz", store.settings.first().displayUnit)
    }

    @Test
    fun fixtureMatchesReferenceCodec() {
        assertTrue(
            LegacyFormat.equivalent(
                root().toString(),
                LegacyFormat.decode(" \n${fixture()}\n").toString(),
            )
        )
        val encoded = LegacyFormat.encode(root())
        assertFalse(encoded.contains('\n'))
        assertTrue(
            LegacyFormat.equivalent(root().toString(), LegacyFormat.decode(encoded).toString())
        )
    }

    @Test
    fun timestampsMinutesQuantitiesAndClassification() {
        val p = legacy.prepare(fixture(), zone)
        assertEquals(3, p.pumpCount)
        assertEquals(1, p.stashCount)
        val first = p.sessions.first()
        assertEquals("1111111111111111", first.id)
        assertEquals(1200L, first.activeSeconds)
        assertEquals(
            LocalDateTime.parse("2024-01-02T09:20:00").atZone(zone).toInstant().toEpochMilli(),
            first.end,
        )
        assertEquals(29574L, first.volume)
        assertEquals("1", Volume.format(first.volume, "oz"))
        assertNull(first.left)
        assertNull(first.right)
        val split = p.sessions[1]
        assertEquals(59147L, split.left)
        assertEquals(88721L, split.right)
        assertEquals(split.left!! + split.right!!, split.volume)
        assertEquals("A synthetic note with Unicode: café", split.notes)
        val noStart = p.sessions[2]
        assertEquals(900000L, noStart.end - noStart.start)
        assertEquals("2024-01-01T15:00:00.123456", JSONObject(p.records[2].json).getString("time"))
        assertTrue(JSONObject(p.records[2].json).isNull("startTime"))
    }

    @Test
    fun idempotentMergeAndAssociations() = runBlocking {
        val p = legacy.prepare(fixture(), zone)
        legacy.import(p)
        val preview = legacy.preview(p)
        assertEquals(3, preview.duplicatePumps)
        assertEquals(1, preview.duplicateStash)
        assertEquals(0, preview.conflicts)
        legacy.import(p)
        assertEquals(3, repo.dao.allSessions().size)
        assertEquals(1, repo.dao.allBags().size)
        assertEquals(1, repo.dao.allMovements().size)
        val b = repo.dao.allBags().single()
        assertEquals(1, b.number)
        assertEquals("2222222222222222", b.sourceSession)
        assertEquals("1", repo.dao.allMovements().single().id)
        assertEquals(29574L, repo.dao.remaining(b.id))
        assertEquals(187442L, repo.dao.allSessions().sumOf { it.volume })
    }

    @Test
    fun mergesWithExistingAndroidSessionsAndKeepsLocalEdits() = runBlocking {
        val s = Session("synthetic-android", 0, 60000, 60, 1000)
        repo.saveSession(s)
        val p = legacy.prepare(fixture(), zone)
        legacy.import(p)
        val imported = repo.dao.session("1111111111111111")!!
        repo.saveSession(imported.copy(notes = "Synthetic local edit"))
        legacy.import(p)
        assertEquals(4, repo.dao.allSessions().size)
        assertEquals("Synthetic local edit", repo.dao.session(imported.id)!!.notes)
        assertEquals(s, repo.dao.session(s.id))
    }

    @Test
    fun exactLegacyFieldsRoundTripIncludingNullsAndMicroseconds() = runBlocking {
        val input = modified { r ->
            r.getJSONObject("settings")
                .put("future_setting", JSONObject().put("a", JSONArray().put("synthetic")))
            r.getJSONObject("data")
                .getJSONArray("pump_records")
                .getJSONObject(0)
                .put("notes", "Synthetic\nUnicode 🌷")
                .put("future", true)
        }
        legacy.import(legacy.prepare(input, zone))
        val actual = LegacyFormat.decode(legacy.export().text)
        val original = LegacyFormat.decode(input)
        assertTrue(
            LegacyFormat.equivalent(
                original.getJSONObject("settings").toString(),
                actual.getJSONObject("settings").toString(),
            )
        )
        val old = LegacyFormat.records(original, "pump_records").associateBy { it.getString("id") }
        val new = LegacyFormat.records(actual, "pump_records").associateBy { it.getString("id") }
        assertEquals(old.keys, new.keys)
        old.forEach { (id, j) ->
            assertTrue(LegacyFormat.equivalent(j.toString(), new[id]!!.toString()))
        }
        assertTrue(
            LegacyFormat.equivalent(
                original
                    .getJSONObject("data")
                    .getJSONArray("stash_records")
                    .getJSONObject(0)
                    .toString(),
                actual
                    .getJSONObject("data")
                    .getJSONArray("stash_records")
                    .getJSONObject(0)
                    .toString(),
            )
        )
    }

    @Test
    fun noteEditsDoNotRoundAwayOriginalAmountsOrTimes() = runBlocking {
        legacy.import(legacy.prepare(fixture(), zone))
        val s = repo.dao.session("3333333333333333")!!
        repo.saveSession(s.copy(notes = "Synthetic edited note"))
        val j =
            LegacyFormat.records(LegacyFormat.decode(legacy.export().text), "pump_records").single {
                it.getString("id") == s.id
            }
        assertEquals("2024-01-01T15:00:00.123456", j.getString("time"))
        assertTrue(j.isNull("startTime"))
        assertEquals("Synthetic edited note", j.getString("notes"))
    }

    @Test
    fun preferencesOptInAndLaterEditsAreRespected() = runBlocking {
        val previousReminder = store.settings.first().nextPump
        store.string("unit", "mL")
        store.bool("dark", false)
        val input = modified { r ->
            r.getJSONObject("settings")
                .put("storage_fridge_days", 5)
                .put("formula_cost_per_100ml", 2.5)
                .put("deep_freezer_enabled", true)
        }
        val p = legacy.prepare(input, zone)
        legacy.import(p)
        assertEquals("mL", store.settings.first().displayUnit)
        assertFalse(store.settings.first().dark)
        legacy.import(p, applySettings = true)
        val s = store.settings.first()
        assertEquals("oz", s.displayUnit)
        assertTrue(s.dark)
        assertEquals(5, s.fridgeDays)
        assertEquals("25.0", s.savingsPerLiter)
        assertTrue(s.deep)
        assertEquals(previousReminder, s.nextPump)
        val originalVolume = repo.dao.session("1111111111111111")!!.volume
        store.string("unit", "mL")
        assertEquals("mL", store.settings.first().displayUnit)
        store.string("unit", "oz")
        assertEquals(originalVolume, repo.dao.session("1111111111111111")!!.volume)
    }

    @Test
    fun changedSourceRecordsConflictWithoutPartialImport() = runBlocking {
        legacy.import(legacy.prepare(fixture(), zone))
        val changed =
            legacy.prepare(
                modified { r ->
                    r.getJSONObject("data")
                        .getJSONArray("pump_records")
                        .getJSONObject(0)
                        .put("notes", "Synthetic changed source")
                },
                zone,
            )
        assertTrue(legacy.preview(changed).conflicts > 0)
        val before = Backup(repo, store).export()
        assertTrue(runCatching { legacy.import(changed, applySettings = true) }.isFailure)
        assertEquals(before, Backup(repo, store).export())
    }

    @Test
    fun replaceRequiresConfirmationThenReplacesAtomically() = runBlocking {
        repo.saveSession(Session("synthetic-android", 0, 60000, 60, 1000))
        repo.addBag(Allocation(1000, "Fridge", 4), 1000)
        val p = legacy.prepare(fixture(), zone)
        assertTrue(legacy.preview(p).conflicts > 0)
        assertTrue(runCatching { legacy.import(p, LegacyImportMode.REPLACE) }.isFailure)
        assertEquals("synthetic-android", repo.dao.allSessions().single().id)
        legacy.import(p, LegacyImportMode.REPLACE, replaceConfirmed = true)
        assertEquals(3, repo.dao.allSessions().size)
        assertEquals(29574L, repo.dao.allMovements().sumOf { it.delta })
        legacy.import(p)
        assertEquals(3, repo.dao.allSessions().size)
    }

    @Test
    fun sqliteFailureRollsBackReplacementAndPreferences() = runBlocking {
        repo.saveSession(Session("synthetic-android", 0, 60000, 60, 1000))
        store.string("unit", "mL")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER synthetic_failure BEFORE INSERT ON legacy_metadata BEGIN SELECT RAISE(ABORT, 'Synthetic failure'); END"
        )
        assertTrue(
            runCatching {
                    legacy.import(
                        legacy.prepare(fixture(), zone),
                        LegacyImportMode.REPLACE,
                        true,
                        true,
                    )
                }
                .isFailure
        )
        assertEquals("synthetic-android", repo.dao.allSessions().single().id)
        assertTrue(repo.dao.allBags().isEmpty())
        assertTrue(repo.dao.legacyRecords().isEmpty())
        assertNull(repo.dao.currentLegacyPreferences())
        assertEquals("mL", store.settings.first().displayUnit)
    }

    @Test
    fun unknownUsageRetainedAndAffectedInventoryNotGuessed() = runBlocking {
        val input = modified { r ->
            val a = r.getJSONObject("data").getJSONArray("stash_records")
            a.put(
                JSONObject(a.getJSONObject(0).toString())
                    .put("id", "synthetic-use")
                    .put("type", "UnverifiedUsed")
                    .put("purpose", "Donation")
                    .put("volume", 5)
            )
        }
        val p = legacy.prepare(input, zone)
        assertEquals(2, p.stashCount)
        assertTrue(p.warnings.any { it.contains("excluded") })
        assertTrue(p.bags.isEmpty())
        legacy.import(p)
        assertEquals(2, repo.dao.legacyRecords().count { it.kind == "stash" })
        assertTrue(repo.dao.allBags().isEmpty())
        val events =
            LegacyFormat.records(LegacyFormat.decode(legacy.export().text), "stash_records")
        assertEquals(2, events.size)
        assertEquals(
            "Donation",
            events.single { it.getString("id") == "synthetic-use" }.getString("purpose"),
        )
    }

    @Test
    fun multipleBagShapeAndMissingReferencesRetainedWithWarning() {
        val p =
            legacy.prepare(
                modified { r ->
                    r.getJSONObject("data")
                        .getJSONArray("pump_records")
                        .getJSONObject(1)
                        .put("stashBagIds", JSONObject().put("future", JSONArray().put(1)))
                        .put("stashBagId", 99)
                },
                zone,
            )
        assertTrue(p.warnings.any { it.contains("stashBagIds") })
        assertTrue(p.warnings.any { it.contains("absent bag") })
        assertNull(p.bags.single().sourceSession)
    }

    @Test
    fun nativeBackupRetainsLegacyMetadataAndIdempotence() = runBlocking {
        legacy.import(legacy.prepare(fixture(), zone))
        val native = Backup(repo, store).export()
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
            Backup(r, s).import(native)
            val l = LegacyBackup(r, s)
            l.import(l.prepare(fixture(), zone))
            assertEquals(3, r.dao.allSessions().size)
            assertEquals(4, r.dao.legacyRecords().size)
            assertEquals(
                4,
                LegacyFormat.records(LegacyFormat.decode(l.export().text), "pump_records").size +
                    LegacyFormat.records(LegacyFormat.decode(l.export().text), "stash_records").size,
            )
        } finally {
            other.close()
        }
    }

    @Test
    fun legacyExportRefusesUnverifiedAndroidWithdrawals() = runBlocking {
        legacy.import(legacy.prepare(fixture(), zone))
        val bag = repo.dao.allBags().single()
        repo.useBag(bag.id, 1000, "Feeding", bag.stored + 1000, "")
        assertTrue(runCatching { legacy.export() }.isFailure)
        assertEquals(2, JSONObject(Backup(repo, store).export()).getJSONArray("movements").length())
    }

    @Test
    fun invalidFilesAndVersionsRejected() {
        val invalid =
            listOf(
                "!!!",
                Base64.getEncoder().encodeToString("wrong key".toByteArray()),
                Base64.getEncoder().encodeToString(ByteArray(100) { 0 }),
                "{}",
            )
        invalid.forEach { assertTrue(runCatching { legacy.prepare(it, zone) }.isFailure) }
        listOf<Any>("2.0", 1).forEach { version ->
            val j = root().put("version", version)
            assertTrue(runCatching { LegacyFormat.encode(j) }.isFailure)
        }
        assertTrue(runCatching { LegacyFormat.encode(root().put("app", "other")) }.isFailure)
    }

    @Test
    fun invalidQuantitiesIdsDatesDuplicatesAndDurationsRejected() {
        val changes =
            listOf<(JSONObject) -> Unit>(
                { it.put("right", -1) },
                { it.put("right", "NaN") },
                { it.put("id", 123) },
                { it.put("id", "") },
                { it.put("time", "2024-01-02T09:20:00Z") },
                { it.put("duration", 0) },
                { it.put("duration", 1.5) },
                { it.put("duration", 99) },
                { it.put("stashAmount", 100) },
            )
        changes.forEach { change ->
            assertTrue(
                runCatching {
                        legacy.prepare(
                            modified { r ->
                                change(
                                    r.getJSONObject("data")
                                        .getJSONArray("pump_records")
                                        .getJSONObject(0)
                                )
                            },
                            zone,
                        )
                    }
                    .isFailure
            )
        }
        assertTrue(
            runCatching {
                    legacy.prepare(
                        modified { r ->
                            val a = r.getJSONObject("data").getJSONArray("pump_records")
                            a.put(a.getJSONObject(0))
                        },
                        zone,
                    )
                }
                .isFailure
        )
    }

    @Test
    fun timezoneAndDstGapsAreExplicit() {
        val p = legacy.prepare(fixture(), ZoneId.of("Asia/Tokyo"))
        assertEquals(
            LocalDateTime.parse("2024-01-02T09:20:00")
                .atZone(ZoneId.of("Asia/Tokyo"))
                .toInstant()
                .toEpochMilli(),
            p.sessions.first().end,
        )
        assertTrue(
            runCatching {
                    legacy.prepare(
                        modified { r ->
                            r.getJSONObject("data")
                                .getJSONArray("pump_records")
                                .getJSONObject(0)
                                .put("startTime", "2024-03-10T02:00:00")
                                .put("time", "2024-03-10T02:20:00")
                        },
                        zone,
                    )
                }
                .isFailure
        )
    }

    @Test
    fun fileSizeAndUtf8LimitsAreEnforced() {
        assertTrue(
            runCatching { LegacyFormat.decode("A".repeat(LegacyFormat.MAX_BYTES + 1)) }.isFailure
        )
        val oversized =
            object : java.io.InputStream() {
                var remaining = LegacyFormat.MAX_BYTES + 1

                override fun read() = if (remaining-- > 0) 65 else -1

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) return -1
                    val n = minOf(remaining, len)
                    java.util.Arrays.fill(b, off, off + n, 65.toByte())
                    remaining -= n
                    return n
                }
            }
        assertTrue(runCatching { BackupFiles.read(oversized) }.isFailure)
        assertTrue(
            runCatching { BackupFiles.read(byteArrayOf(0xC3.toByte(), 0x28).inputStream()) }
                .isFailure
        )
    }

    @Test
    fun safetyBackupCanRestoreSupportedPreferencesAndOldNativeFormatStillWorks() = runBlocking {
        legacy.import(legacy.prepare(fixture(), zone), applySettings = true)
        val native = Backup(repo, store).export()
        store.string("unit", "mL")
        Backup(repo, store).import(native)
        assertEquals("mL", store.settings.first().displayUnit)
        Backup(repo, store).import(native, applyLegacySettings = true)
        assertEquals("oz", store.settings.first().displayUnit)
        val old = JSONObject(native)
        listOf("preferences_snapshot", "legacy_records", "legacy_metadata", "legacy_preferences")
            .forEach { old.remove(it) }
        val other =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    PumpDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
        try {
            val r = Repository(other)
            Backup(r, SettingsStore(ApplicationProvider.getApplicationContext(), other.dao()))
                .import(old.toString())
            assertEquals(3, r.dao.allSessions().size)
            assertEquals(1, r.dao.allBags().size)
            assertTrue(r.dao.legacyRecords().isEmpty())
        } finally {
            other.close()
        }
    }

    @Test
    fun tooManyRecordsDeepJsonAndTrailingContentAreRejected() {
        val r = root()
        val a = JSONArray()
        repeat(LegacyFormat.MAX_RECORDS + 1) { a.put(JSONObject()) }
        r.getJSONObject("data").put("pump_records", a)
        assertTrue(runCatching { LegacyFormat.encode(r) }.isFailure)
        assertTrue(
            runCatching {
                    LegacyFormat.parseJson("{\"a\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}")
                }
                .isFailure
        )
        assertTrue(
            runCatching { LegacyFormat.parseJson(root().toString() + " trailing") }.isFailure
        )
    }

    @Test
    fun nativeRecordsExportToDocumentedLegacySchema() = runBlocking {
        val start =
            LocalDateTime.parse("2024-02-02T10:00:00").atZone(zone).toInstant().toEpochMilli()
        repo.saveSession(
            Session(
                "synthetic-native",
                start,
                start + 1200000,
                1200,
                1000,
                notes = "Synthetic native\n🌷",
            ),
            listOf(Allocation(1000, "Fridge", 4)),
        )
        val doc = LegacyFormat.decode(legacy.export().text)
        assertEquals("1.0", doc.getString("version"))
        assertEquals("breastmilk", doc.getString("app"))
        assertEquals(19, doc.getJSONObject("settings").length())
        assertTrue(Regex(".*\\.\\d{6}").matches(doc.getString("export_date")))
        val p = LegacyFormat.records(doc, "pump_records").single()
        assertEquals("synthetic-native", p.getString("id"))
        assertEquals(20, p.getInt("duration"))
        assertEquals(1.0, p.getDouble("right"), 0.0)
        assertEquals("Synthetic native\n🌷", p.getString("notes"))
        assertEquals(1, p.getInt("stashBagId"))
        assertEquals(1.0, p.getDouble("stashAmount"), 0.0)
        val b = LegacyFormat.records(doc, "stash_records").single()
        assertEquals("Added", b.getString("type"))
        assertEquals("fridge", b.getString("location"))
        assertEquals(repo.dao.allMovements().single().id, b.getString("id"))
    }

    @Test
    fun nonrepresentableAndroidFeaturesRequireNativeBackup() = runBlocking {
        repo.saveSession(Session("synthetic-short", 0, 59000, 59, 1000))
        assertTrue(runCatching { legacy.export() }.isFailure)
        repo.deleteSession("synthetic-short")
        repo.saveSession(
            Session("synthetic-split", 0, 60000, 60, 2000),
            listOf(Allocation(1000, "Fridge", 4), Allocation(1000, "Freezer", 180)),
        )
        assertTrue(runCatching { legacy.export() }.isFailure)
        assertEquals(2, JSONObject(Backup(repo, store).export()).getJSONArray("bags").length())
    }

    @Test
    fun associationsCompareNumericalAmountsRatherThanDecimalScale() {
        val p =
            legacy.prepare(
                modified { r ->
                    r.getJSONObject("data")
                        .getJSONArray("pump_records")
                        .getJSONObject(1)
                        .put("stashAmount", 1.0)
                    r.getJSONObject("data")
                        .getJSONArray("stash_records")
                        .getJSONObject(0)
                        .put("volume", 1)
                },
                zone,
            )
        assertEquals("2222222222222222", p.bags.single().sourceSession)
        assertEquals(1000L, p.bags.single().initial)
    }

    @Test
    fun newUnverifiedUsageCannotLeavePreviouslyImportedBagAvailable() = runBlocking {
        legacy.import(legacy.prepare(fixture(), zone))
        val later =
            legacy.prepare(
                modified { r ->
                    val a = r.getJSONObject("data").getJSONArray("stash_records")
                    a.put(
                        JSONObject(a.getJSONObject(0).toString())
                            .put("id", "synthetic-later-use")
                            .put("type", "UnverifiedUsed")
                            .put("volume", 5)
                    )
                },
                zone,
            )
        assertTrue(legacy.preview(later).conflicts > 0)
        val before = Backup(repo, store).export()
        assertTrue(runCatching { legacy.import(later) }.isFailure)
        assertEquals(before, Backup(repo, store).export())
        legacy.import(later, LegacyImportMode.REPLACE, replaceConfirmed = true)
        assertTrue(repo.dao.allBags().isEmpty())
        assertEquals(2, repo.dao.legacyRecords().count { it.kind == "stash" })
        legacy.import(later)
        assertEquals(2, repo.dao.legacyRecords().count { it.kind == "stash" })
    }

    @Test
    fun uninterpretedBagsKeepTheirNumbersReserved() = runBlocking {
        val unknown =
            legacy.prepare(
                modified { r ->
                    r.getJSONObject("data")
                        .getJSONArray("stash_records")
                        .getJSONObject(0)
                        .put("type", "UnverifiedEvent")
                },
                zone,
            )
        legacy.import(unknown)
        assertTrue(repo.dao.allBags().isEmpty())
        assertEquals(2, repo.dao.nextNumber())
        repo.addBag(Allocation(1000, "Fridge", 4), System.currentTimeMillis())
        assertEquals(2, repo.dao.allBags().single().number)
        val nextFile =
            legacy.prepare(
                modified { r ->
                    r.getJSONObject("data")
                        .getJSONArray("stash_records")
                        .getJSONObject(0)
                        .put("id", "different-source-bag")
                },
                zone,
            )
        assertTrue(legacy.preview(nextFile).conflicts > 0)
    }

    @Test
    fun nullAndMissingSettingsDoNotResetEarlierImportedPreferences() = runBlocking {
        val first = modified { r ->
            r.getJSONObject("settings")
                .put("volume_unit", "mL")
                .put("dark_mode", false)
                .put("storage_freezer_days", 333)
        }
        legacy.import(legacy.prepare(first, zone), applySettings = true)
        val next = modified { r ->
            val s = r.getJSONObject("settings")
            s.remove("volume_unit")
            s.remove("dark_mode")
            s.put("storage_freezer_days", JSONObject.NULL)
            s.put("storage_fridge_days", 5)
        }
        legacy.import(legacy.prepare(next, zone), applySettings = true)
        val actual = store.settings.first()
        assertEquals("mL", actual.displayUnit)
        assertFalse(actual.dark)
        assertEquals(333, actual.freezerDays)
        assertEquals(5, actual.fridgeDays)
    }
}
