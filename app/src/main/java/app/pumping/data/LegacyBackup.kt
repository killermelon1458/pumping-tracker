package app.pumping.data

import androidx.room.withTransaction
import app.pumping.domain.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import org.json.*

enum class LegacyImportMode {
    MERGE,
    REPLACE,
}

class LegacyPlan
internal constructor(
    val root: String,
    val zone: String,
    val sessions: List<Session>,
    val bags: List<Bag>,
    val movements: List<Movement>,
    val records: List<LegacyRecord>,
    val warnings: List<String>,
    val preferences: String,
) {
    val pumpCount
        get() = records.count { it.kind == "pump" }

    val stashCount
        get() = records.count { it.kind == "stash" }
}

data class LegacyPreview(
    val plan: LegacyPlan,
    val duplicatePumps: Int,
    val duplicateStash: Int,
    val conflicts: Int,
    val deletedPumps: Int = 0,
    val deletedStash: Int = 0,
)

data class LegacyExport(val text: String, val warnings: List<String>)

class LegacyBackup(private val repo: Repository, private val settings: SettingsStore) {
    private val legacyTimestamp = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS")

    fun prepare(text: String, zone: ZoneId = ZoneId.systemDefault()): LegacyPlan =
        parse(LegacyFormat.decode(text), zone)

    private fun parse(root: JSONObject, zone: ZoneId): LegacyPlan {
        LegacyFormat.validateRoot(root)
        val pumpJson = LegacyFormat.records(root, "pump_records")
        val stashJson = LegacyFormat.records(root, "stash_records")
        val warnings = linkedSetOf<String>()
        val sessions =
            pumpJson.map { j ->
                val id = LegacyFormat.id(j.opt("id"))
                val minutes = LegacyFormat.integer(j.opt("duration"), 1, 525600)
                val endLocal = LegacyFormat.local(j.opt("time"))
                val startLocal =
                    if (j.isNull("startTime")) endLocal.minusMinutes(minutes.toLong())
                    else LegacyFormat.local(j.get("startTime"))
                require(!startLocal.isAfter(endLocal) && startLocal != endLocal) {
                    "A session start must precede its end."
                }
                if (!j.isNull("startTime"))
                    require(
                        Duration.between(startLocal, endLocal) ==
                            Duration.ofMinutes(minutes.toLong())
                    ) {
                        "Session timestamps disagree with its duration."
                    }
                val start = LegacyFormat.instant(startLocal.toString(), zone)
                val end = LegacyFormat.instant(j.get("time"), zone)
                require(end > start) { "Session is invalid in the selected time zone." }
                val left = LegacyFormat.microliters(j.opt("left"))
                val right = LegacyFormat.microliters(j.opt("right"))
                val total = Math.addExact(left, right)
                require(total <= Volume.MAX) { "Session quantity is too large." }
                val stash = LegacyFormat.number(j.opt("stashAmount"))
                require(
                    stash <=
                        LegacyFormat.number(j.get("left")) + LegacyFormat.number(j.get("right"))
                ) {
                    "A session assigns more milk to stash than its output."
                }
                if (!j.isNull("stashBagId"))
                    LegacyFormat.integer(j.get("stashBagId"), 1, Int.MAX_VALUE - 1)
                if (!j.isNull("stashBagIds"))
                    warnings +=
                        "Multiple-bag references (stashBagIds) are retained verbatim; their undocumented structure is not interpreted."
                LegacyFormat.integer(j.opt("isMarked"), 0, 1)
                if (
                    zone.rules.getValidOffsets(startLocal).size > 1 ||
                        zone.rules.getValidOffsets(endLocal).size > 1
                )
                    warnings +=
                        "Ambiguous daylight-saving times use the earlier offset; original wall-clock strings are retained."
                // Naive source durations can span a DST transition. Retain the exact source value
                // in raw data.
                val seconds = minOf(minutes * 60L, (end - start) / 1000)
                if (seconds != minutes * 60L)
                    warnings +=
                        "A session crosses a daylight-saving transition. Its original duration is retained for export; active time is capped to its real timestamp span."
                Session(
                        id,
                        start,
                        end,
                        seconds,
                        total,
                        if (left > 0) left else null,
                        if (left > 0) right else null,
                        LegacyFormat.text(j, "notes"),
                        "LEGACY",
                        end,
                        end,
                    )
                    .also(::validateSession)
            }
        require(sessions.map { it.id }.toSet().size == sessions.size) {
            "Duplicate pump IDs within the backup. Nothing was imported."
        }
        val stashIds = stashJson.map { LegacyFormat.id(it.opt("id")) }
        require(stashIds.toSet().size == stashIds.size) {
            "Duplicate stash event IDs within the backup. Nothing was imported."
        }
        val unsupportedBags = mutableSetOf<Int>()
        stashJson.forEach { j ->
            val number = LegacyFormat.integer(j.opt("bagId"), 1, Int.MAX_VALUE - 1)
            LegacyFormat.number(j.opt("volume"))
            val date = LegacyFormat.instant(j.opt("date"), zone)
            val expires =
                if (j.isNull("expiryDate")) null
                else LegacyFormat.instant(j.get("expiryDate"), zone)
            if (j.opt("type") == "Added")
                require(expires != null && expires >= date) {
                    "A stash expiry precedes its storage date or is missing."
                }
            LegacyFormat.text(j, "notes")
            val type = LegacyFormat.text(j, "type")
            val location = LegacyFormat.text(j, "location")
            if (type != "Added" || location !in listOf("fridge", "freezer")) {
                unsupportedBags += number
                warnings +=
                    "Unverified stash event type or location retained for re-export. Affected bags are excluded from available inventory rather than guessing their balance."
            }
            if (!j.isNull("purpose") || !j.isNull("brickId"))
                warnings +=
                    "Stash purpose and brickId values are preserved but have no confirmed Android mapping."
        }
        val additions = stashJson.filter { it.getString("type") == "Added" }
        require(additions.map { it.getInt("bagId") }.toSet().size == additions.size) {
            "Multiple Added records share a bag number. Resolve the source ambiguity before importing."
        }
        val pumpsByBag =
            pumpJson.filter { !it.isNull("stashBagId") }.groupBy { it.getInt("stashBagId") }
        val stashNumbers = stashJson.map { it.getInt("bagId") }.toSet()
        val bags = mutableListOf<Bag>()
        val movements = mutableListOf<Movement>()
        stashJson.forEach { j ->
            val number = j.getInt("bagId")
            if (number !in unsupportedBags) {
                val amount = LegacyFormat.microliters(j.get("volume"))
                require(amount > 0) { "Added bags must contain a positive quantity." }
                val linked = pumpsByBag[number].orEmpty()
                val matched =
                    linked.singleOrNull()?.takeIf {
                        LegacyFormat.number(it.get("stashAmount"))
                            .compareTo(LegacyFormat.number(j.get("volume"))) == 0
                    }
                if (linked.isNotEmpty() && matched == null)
                    warnings +=
                        "An inconsistent or ambiguous pump-to-bag link was retained in the source record but not inferred in inventory."
                val id = j.getString("id")
                val stored = LegacyFormat.instant(j.get("date"), zone)
                val b =
                    Bag(
                        "legacy:bag:$id",
                        number,
                        stored,
                        amount,
                        when (j.getString("location")) {
                            "fridge" -> "Fridge"
                            "freezer" -> "Freezer"
                            else -> "Deep Freezer"
                        },
                        LegacyFormat.instant(j.get("expiryDate"), zone),
                        j.getString("notes"),
                        matched?.getString("id"),
                    )
                bags += b
                movements += Movement(id, b.id, stored, "ADD", amount, j.getString("notes"))
            }
        }
        pumpJson.forEach { j ->
            if (!j.isNull("stashBagId") && j.getInt("stashBagId") !in stashNumbers)
                warnings +=
                    "A pump references an absent bag; its link and assigned quantity are preserved without inventing inventory."
            if (
                LegacyFormat.number(j.get("stashAmount")) > BigDecimal.ZERO &&
                    j.isNull("stashBagId")
            )
                warnings +=
                    "A stash amount without a confirmed bag link is retained; it does not create inventory."
        }
        val sessionsById = sessions.associateBy { it.id }
        val bagsById = bags.associateBy { it.id }
        val records =
            pumpJson.map { j ->
                val s = sessionsById.getValue(j.getString("id"))
                LegacyRecord("pump", s.id, j.toString(), sessionJson(s).toString(), zone.id)
            } +
                stashJson.map { j ->
                    val b = bagsById["legacy:bag:${j.getString("id")}"]
                    LegacyRecord(
                        "stash",
                        j.getString("id"),
                        j.toString(),
                        b?.let { bagJson(it).toString() } ?: "{}",
                        zone.id,
                    )
                }
        val preferences = supportedSettings(root.getJSONObject("settings"), warnings)
        if (
            records.any {
                it.kind == "pump" &&
                    LegacyFormat.number(JSONObject(it.json).get("left")) > BigDecimal.ZERO &&
                    LegacyFormat.microliters(JSONObject(it.json).get("left")) == 0L
            }
        )
            warnings +=
                "Very small left-side quantities are retained exactly in the original record."
        warnings +=
            "Android displays quantities to the nearest microliter and times to milliseconds; exact source values and microseconds are retained for unchanged fields on legacy export."
        warnings +=
            "Reminder schedules, sound/vibration, night rules, interval_from_start and pump_parts_reminders are retained for export, not activated."
        return LegacyPlan(
            root.toString(),
            zone.id,
            sessions,
            bags,
            movements,
            records,
            warnings.toList(),
            preferences.toString(),
        )
    }

    private fun supportedSettings(j: JSONObject, warnings: MutableSet<String>): JSONObject {
        val result = JSONObject()
        if (j.has("version") && !j.isNull("version"))
            require(LegacyFormat.integer(j.get("version"), 1, Int.MAX_VALUE) == 1) {
                "Unsupported legacy settings version."
            }
        if (!j.isNull("volume_unit")) {
            val unit = LegacyFormat.text(j, "volume_unit")
            if (unit.lowercase() in listOf("oz", "ml"))
                result.put("unit", if (unit.lowercase() == "ml") "mL" else "oz")
            else warnings += "Unsupported volume unit retained; current unit will be kept."
        }
        listOf("dark_mode" to "dark", "deep_freezer_enabled" to "deep").forEach { (source, target)
            ->
            if (!j.isNull(source)) {
                require(j.get(source) is Boolean) {
                    "A supported setting has an invalid boolean value."
                }
                result.put(target, j.getBoolean(source))
            }
        }
        listOf(
                "storage_fridge_days" to "fridge",
                "storage_freezer_days" to "freezer",
                "storage_deep_freezer_days" to "deepDays",
            )
            .forEach { (source, target) ->
                if (!j.isNull(source))
                    result.put(target, LegacyFormat.integer(j.get(source), 1, 3650))
            }
        if (!j.isNull("formula_cost_per_100ml"))
            result.put(
                "savings",
                LegacyFormat.number(j.get("formula_cost_per_100ml"))
                    .multiply(BigDecimal.TEN)
                    .toPlainString(),
            )
        return result
    }

    private suspend fun retained(plan: LegacyPlan): LegacyPlan {
        val deleted = DeletedRecords(repo.dao.deletions())
        val sessions = plan.sessions.filterNot { deleted.session(it.id) }
        val bags =
            plan.bags
                .filterNot {
                    deleted.bag(it.id) || deleted.stash(it.id.removePrefix("legacy:bag:"))
                }
                .map { b ->
                    if (b.sourceSession?.let { deleted.session(it) } == true)
                        b.copy(sourceSession = null)
                    else b
                }
        val ids = bags.map { it.id }.toSet()
        return LegacyPlan(
            plan.root,
            plan.zone,
            sessions,
            bags,
            plan.movements.filter { it.bagId in ids && !deleted.movement(it.id) },
            plan.records.filterNot { deleted.record(it) },
            plan.warnings,
            plan.preferences,
        )
    }

    suspend fun preview(plan: LegacyPlan): LegacyPreview =
        repo.db.withTransaction {
            val existing = repo.dao.legacyRecords().associateBy { it.kind to it.id }
            val deleted = DeletedRecords(repo.dao.deletions())
            val active = retained(plan)
            val duplicate =
                plan.records.filter { r ->
                    deleted.record(r) ||
                        existing[r.kind to r.id]?.let { deleted.equivalent(it, r) } == true
                }
            val oldSessions = repo.dao.allSessions().associateBy { it.id }
            val oldBags = repo.dao.allBags().associateBy { it.number }
            val oldMoves = repo.dao.allMovements().associateBy { it.id }
            val oldSourceNumbers =
                existing.values
                    .filter { it.kind == "stash" }
                    .groupBy { JSONObject(it.json).getInt("bagId") }
            val sourceNumberConflicts =
                active.bags.count { b ->
                    oldSourceNumbers[b.number]?.none {
                        it.id == b.id.removePrefix("legacy:bag:")
                    } == true
                }
            val unresolved =
                active.records
                    .filter { it.kind == "stash" && it.projection == "{}" }
                    .map { JSONObject(it.json).getInt("bagId") }
                    .toSet()
            val conflicts =
                unresolved.count { oldBags[it]?.id?.startsWith("legacy:bag:") == true } +
                    sourceNumberConflicts +
                    active.records.count { r ->
                        existing[r.kind to r.id]?.let { !deleted.equivalent(it, r) } ?: false
                    } +
                    active.sessions.count {
                        it.id in oldSessions && ("pump" to it.id) !in existing
                    } +
                    active.bags.count { b -> oldBags[b.number]?.let { it.id != b.id } == true } +
                    active.movements.count { it.id in oldMoves && ("stash" to it.id) !in existing }
            LegacyPreview(
                plan,
                duplicate.count { it.kind == "pump" },
                duplicate.count { it.kind == "stash" },
                conflicts,
                plan.records.count { it.kind == "pump" && deleted.record(it) },
                plan.records.count { it.kind == "stash" && deleted.record(it) },
            )
        }

    suspend fun import(
        plan: LegacyPlan,
        mode: LegacyImportMode = LegacyImportMode.MERGE,
        applySettings: Boolean = false,
        replaceConfirmed: Boolean = false,
    ) {
        require(mode != LegacyImportMode.REPLACE || replaceConfirmed) {
            "Replace requires explicit confirmation and a safety backup."
        }
        // Revalidate rather than trusting mutable objects used by a preview.
        val checked = parse(JSONObject(plan.root), ZoneId.of(plan.zone))
        val current = settings.settings.first()
        repo.db.withTransaction {
            if (mode == LegacyImportMode.REPLACE) {
                repo.dao.clearMovements()
                repo.dao.clearBags()
                repo.dao.clearSessions()
                repo.dao.clearLegacyRecords()
                repo.dao.clearLegacyMetadata()
                repo.dao.clearDeletions()
            }
            val check = preview(checked)
            require(check.conflicts == 0) {
                "Backup conflicts with existing IDs, bag numbers, or unverified events affecting existing inventory. Merge keeps your data unchanged. Export a safety backup and choose Replace to use this file instead."
            }
            val old = repo.dao.legacyRecords().associateBy { it.kind to it.id }
            val active = retained(checked)
            active.sessions
                .filter { ("pump" to it.id) !in old }
                .forEach { repo.dao.insertSession(it) }
            active.bags
                .filter { b -> ("stash" to b.id.removePrefix("legacy:bag:")) !in old }
                .forEach { repo.dao.insertBag(it) }
            active.movements
                .filter { ("stash" to it.id) !in old }
                .forEach { repo.dao.insertMovement(it) }
            active.records
                .filter { (it.kind to it.id) !in old }
                .forEach { repo.dao.putLegacyRecord(it) }
            val root = JSONObject(checked.root)
            root
                .getJSONObject("data")
                .put("pump_records", JSONArray())
                .put("stash_records", JSONArray())
            val fingerprint =
                MessageDigest.getInstance("SHA-256")
                    .digest(checked.root.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            val snapshot = settingsJson(current)
            if (applySettings) {
                val prefs = JSONObject(checked.preferences)
                repo.dao.putLegacyPreferences(
                    LegacyPreferences(
                        token = newId(),
                        json = mergeLegacyPreferences(current, prefs).toString(),
                    )
                )
                applySettingsSnapshot(snapshot, prefs)
            }
            repo.dao.putLegacyMetadata(
                LegacyMetadata(
                    "root:$fingerprint",
                    JSONObject().put("root", root).put("settingsSnapshot", snapshot).toString(),
                )
            )
            repo.dao.putLegacyMetadata(LegacyMetadata("latest", fingerprint))
            repo.dao.reconcileDeletedLinks()
            repo.dao.synchronizeLegacyReservations()
        }
    }

    private fun settingsJson(s: Settings) =
        JSONObject()
            .put("volume_unit", s.displayUnit)
            .put("dark_mode", s.dark)
            .put("storage_fridge_days", s.fridgeDays)
            .put("storage_freezer_days", s.freezerDays)
            .put("storage_deep_freezer_days", s.deepDays)
            .put("deep_freezer_enabled", s.deep)
            .put("formula_cost_per_100ml", s.savingsPerLiter.toBigDecimal().divide(BigDecimal.TEN))

    private fun applySettingsSnapshot(snapshot: JSONObject, prefs: JSONObject) {
        mapOf(
                "unit" to "volume_unit",
                "displayUnit" to "volume_unit",
                "dark" to "dark_mode",
                "fridge" to "storage_fridge_days",
                "freezer" to "storage_freezer_days",
                "deepDays" to "storage_deep_freezer_days",
                "deep" to "deep_freezer_enabled",
            )
            .forEach { (k, v) -> if (prefs.has(k)) snapshot.put(v, prefs.get(k)) }
        if (prefs.has("savings"))
            snapshot.put(
                "formula_cost_per_100ml",
                prefs.getString("savings").toBigDecimal().divide(BigDecimal.TEN),
            )
    }

    suspend fun export(): LegacyExport {
        val currentSettings = settings.settings.first()
        return repo.db.withTransaction {
            repo.dao.reconcileDeletedLinks()
            val deleted = DeletedRecords(repo.dao.deletions())
            val raw = repo.dao.legacyRecords().filterNot { deleted.record(it) }
            val indexedRaw = raw.associateBy { it.kind to it.id }
            val meta = repo.dao.legacyMetadata().associateBy { it.id }
            val latest = meta["latest"]?.json?.let { meta["root:$it"] }?.let { JSONObject(it.json) }
            val root =
                latest?.getJSONObject("root")
                    ?: JSONObject()
                        .put("version", "1.0")
                        .put("app", "breastmilk")
                        .put("data", JSONObject())
                        .put("settings", defaultSettings())
            val sourceSettings = root.getJSONObject("settings")
            val current = settingsJson(currentSettings)
            val baseline = latest?.getJSONObject("settingsSnapshot")
            current.keys().forEach { k ->
                if (baseline == null || current.get(k).toString() != baseline.opt(k)?.toString())
                    sourceSettings.put(k, current.get(k))
            }
            root.put("export_date", LocalDateTime.now().format(legacyTimestamp))
            val sessions = repo.dao.allSessions()
            val bags = repo.dao.allBags()
            val moves = repo.dao.allMovements()
            require(moves.all { it.kind == "ADD" }) {
                "Legacy export cannot safely represent Android withdrawals or transfers. Export complete records (JSON) to preserve your inventory history."
            }
            val linkedBags = bags.groupBy { it.sourceSession }
            val additionsByBag = moves.groupBy { it.bagId }
            val pumps = JSONArray()
            sessions.forEach { s ->
                val original = indexedRaw["pump" to s.id]
                val projected = original?.let { JSONObject(it.projection) }
                val j =
                    original?.let { JSONObject(it.json) }
                        ?: JSONObject()
                            .put("id", s.id)
                            .put("stashAmount", 0)
                            .put("stashBagId", JSONObject.NULL)
                            .put("stashBagIds", JSONObject.NULL)
                            .put("isMarked", 0)
                fun changed(field: String, v: Any?) =
                    projected == null ||
                        projected.opt(field)?.toString() != (v ?: JSONObject.NULL).toString()
                val zone = original?.let { ZoneId.of(it.zone) } ?: ZoneId.systemDefault()
                fun local(t: Long) =
                    Instant.ofEpochMilli(t).atZone(zone).toLocalDateTime().format(legacyTimestamp)
                if (changed("start", s.start)) j.put("startTime", local(s.start))
                if (changed("end", s.end)) j.put("time", local(s.end))
                if (changed("activeSeconds", s.activeSeconds) || projected == null) {
                    require(
                        s.activeSeconds % 60 == 0L && s.end - s.start == s.activeSeconds * 1000
                    ) {
                        "Legacy export cannot safely represent paused or sub-minute Android sessions. Use complete records (JSON)."
                    }
                    j.put("duration", s.activeSeconds / 60)
                }
                if (
                    changed("volume", s.volume) ||
                        changed("left", s.left) ||
                        changed("right", s.right)
                ) {
                    j.put("left", BigDecimal(s.left ?: 0).movePointLeft(3))
                    j.put("right", BigDecimal(s.right ?: s.volume).movePointLeft(3))
                }
                if (changed("notes", s.notes)) j.put("notes", s.notes)
                val linked = linkedBags[s.id].orEmpty()
                if (
                    original == null ||
                        deleted.contains("session-allocation", s.id) ||
                        linked.any { b ->
                            ("stash" to b.id.removePrefix("legacy:bag:")) !in indexedRaw
                        }
                ) {
                    require(linked.size <= 1) {
                        "Legacy multiple-bag serialization is unverified; use complete records (JSON)."
                    }
                    j.put("stashAmount", BigDecimal(linked.sumOf { it.initial }).movePointLeft(3))
                        .put("stashBagId", linked.singleOrNull()?.number ?: JSONObject.NULL)
                }
                pumps.put(j)
            }
            val stash = JSONArray()
            raw.filter { it.kind == "stash" && it.projection == "{}" }
                .forEach { stash.put(JSONObject(it.json)) }
            bags.forEach { b ->
                require(b.location in listOf("Fridge", "Freezer")) {
                    "Legacy deep-freezer serialization is unverified. Use complete records (JSON)."
                }
                val add =
                    additionsByBag[b.id]?.singleOrNull { it.kind == "ADD" }
                        ?: error("Bag ledger cannot be represented in the legacy format. Use JSON.")
                val original = indexedRaw["stash" to add.id]
                val projected = original?.let { JSONObject(it.projection) }
                val zone = original?.let { ZoneId.of(it.zone) } ?: ZoneId.systemDefault()
                fun local(t: Long) =
                    Instant.ofEpochMilli(t).atZone(zone).toLocalDateTime().format(legacyTimestamp)
                val j =
                    original?.let { JSONObject(it.json) }
                        ?: JSONObject()
                            .put("id", add.id)
                            .put("type", "Added")
                            .put("purpose", JSONObject.NULL)
                            .put("brickId", JSONObject.NULL)
                fun changed(k: String, v: Any) =
                    projected == null || projected.opt(k)?.toString() != v.toString()
                if (changed("number", b.number)) j.put("bagId", b.number)
                if (changed("initial", b.initial))
                    j.put("volume", BigDecimal(b.initial).movePointLeft(3))
                if (changed("stored", b.stored)) j.put("date", local(b.stored))
                if (changed("expires", b.expires)) j.put("expiryDate", local(b.expires))
                if (changed("location", b.location))
                    j.put(
                        "location",
                        when (b.location) {
                            "Fridge" -> "fridge"
                            "Freezer" -> "freezer"
                            else -> "deep_freezer"
                        },
                    )
                if (changed("notes", b.notes)) j.put("notes", b.notes)
                stash.put(j)
            }
            root.getJSONObject("data").put("pump_records", pumps).put("stash_records", stash)
            LegacyExport(
                LegacyFormat.encode(root),
                listOf(
                    "Legacy files are obfuscated, not encrypted. Acceptance by the original iPhone app is unverified.",
                    "Original unknown fields are retained. Android-only settings and features require the native JSON backup.",
                ),
            )
        }
    }

    private fun defaultSettings(): JSONObject =
        JSONObject()
            .put("version", 1)
            .put("install_date", LocalDateTime.now().format(legacyTimestamp))
            .put("reminder_type", "ReminderType.none")
            .put("reminder_custom_interval", 3.0)
            .put("reminder_custom_times", JSONArray())
            .put("reminder_sound_enabled", true)
            .put("reminder_vibration_enabled", true)
            .put("reminder_night_mode_enabled", false)
            .put("reminder_night_start", "22:0")
            .put("reminder_night_end", "7:0")
            .put("interval_from_start", JSONObject.NULL)
            .put("pump_parts_reminders", JSONObject.NULL)
}
