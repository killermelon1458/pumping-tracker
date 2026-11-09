package app.pumping.data

import androidx.room.withTransaction
import app.pumping.domain.*
import kotlinx.coroutines.flow.first
import org.json.*

/** Versioned local backup. Conflicting IDs or bag numbers reject the entire import. */
class Backup(private val repo: Repository, private val settings: SettingsStore) {
    suspend fun export(): String {
        val current = settings.settings.first()
        val preferences =
            JSONObject()
                .put("inputUnit", current.inputUnit)
                .put("displayUnit", current.displayUnit)
                .put("dark", current.dark)
                .put("deep", current.deep)
                .put("fridge", current.fridgeDays)
                .put("freezer", current.freezerDays)
                .put("deepDays", current.deepDays)
                .put("savings", current.savingsPerLiter)
        return repo.db.withTransaction {
            repo.dao.reconcileDeletedLinks()
            repo.dao.synchronizeLegacyReservations()
            JSONObject()
                .put("format", "Pumping")
                .put("version", 3)
                .put("preferences_snapshot", preferences)
                .put("pump_profiles", JSONArray().apply {
                    repo.dao.allPumpProfiles().forEach { p -> put(JSONObject()
                        .put("id", p.id).put("type", p.type.name).put("nickname", p.nickname)
                        .put("customType", p.customType).put("brand", p.brand).put("model", p.model)
                        .put("notes", p.notes).put("archived", p.archived)) }
                })
                .put(
                    "deletions",
                    JSONArray().apply {
                        repo.dao.deletions().forEach { d ->
                            put(JSONObject().put("kind", d.kind).put("id", d.id))
                        }
                    },
                )
                .put(
                    "sessions",
                    JSONArray().apply {
                        repo.dao.allSessions().forEach { s ->
                            put(
                                JSONObject()
                                    .put("id", s.id)
                                    .put("start", s.start)
                                    .put("end", s.end)
                                    .put("activeSeconds", s.activeSeconds)
                                    .put("volume", s.volume)
                                    .put("left", s.left ?: JSONObject.NULL)
                                    .put("right", s.right ?: JSONObject.NULL)
                                    .put("notes", s.notes)
                                    .put("mode", s.mode)
                                    .put("created", s.created)
                                    .put("updated", s.updated)
                                    .put("breastfedBefore", s.breastfedBefore.name)
                                    .put("pumpProfileId", s.pumpProfileId ?: JSONObject.NULL)
                            )
                        }
                    },
                )
                .put(
                    "bags",
                    JSONArray().apply {
                        repo.dao.allBags().forEach { b ->
                            put(
                                JSONObject()
                                    .put("id", b.id)
                                    .put("number", b.number)
                                    .put("stored", b.stored)
                                    .put("initial", b.initial)
                                    .put("location", b.location)
                                    .put("expires", b.expires)
                                    .put("notes", b.notes)
                                    .put("sourceSession", b.sourceSession ?: JSONObject.NULL)
                            )
                        }
                    },
                )
                .put(
                    "movements",
                    JSONArray().apply {
                        repo.dao.allMovements().forEach { m ->
                            put(
                                JSONObject()
                                    .put("id", m.id)
                                    .put("bagId", m.bagId)
                                    .put("time", m.time)
                                    .put("kind", m.kind)
                                    .put("delta", m.delta)
                                    .put("notes", m.notes)
                                    .put("oldLocation", m.oldLocation ?: JSONObject.NULL)
                                    .put("newLocation", m.newLocation ?: JSONObject.NULL)
                            )
                        }
                    },
                )
                .put(
                    "legacy_records",
                    JSONArray().apply {
                        repo.dao.legacyRecords().forEach { r ->
                            put(
                                JSONObject()
                                    .put("kind", r.kind)
                                    .put("id", r.id)
                                    .put("json", r.json)
                                    .put("projection", r.projection)
                                    .put("zone", r.zone)
                            )
                        }
                    },
                )
                .put(
                    "legacy_metadata",
                    JSONArray().apply {
                        repo.dao.legacyMetadata().forEach { r ->
                            put(JSONObject().put("id", r.id).put("json", r.json))
                        }
                    },
                )
                .put(
                    "legacy_preferences",
                    repo.dao.currentLegacyPreferences()?.let {
                        JSONObject().put("token", it.token).put("json", it.json)
                    } ?: JSONObject.NULL,
                )
                .toString(2)
        }
    }

    suspend fun csv(): String = buildString {
        append(
            "id,start_utc,end_utc,active_seconds,volume_microliters,left_microliters,right_microliters,notes\r\n"
        )
        repo.dao.allSessions().forEach { s ->
            val values =
                listOf(
                    s.id,
                    java.time.Instant.ofEpochMilli(s.start),
                    java.time.Instant.ofEpochMilli(s.end),
                    s.activeSeconds,
                    s.volume,
                    s.left ?: "",
                    s.right ?: "",
                    s.notes,
                )
            append(values.joinToString(",") { "\"${it.toString().replace("\"", "\"\"")}\"" })
            append("\r\n")
        }
    }

    suspend fun import(text: String, applyLegacySettings: Boolean = false) {
        require(text.length <= LegacyFormat.MAX_BYTES) { "Backup exceeds 20 MB." }
        val root = LegacyFormat.parseJson(text)
        val version = root.getInt("version")
        require(root.getString("format") == "Pumping" && version in 1..3) {
            "Unsupported backup format"
        }
        validateNativeBackupFields(root, version)
        fun JSONObject.nullLong(k: String) = if (isNull(k)) null else getLong(k)
        fun JSONObject.nullString(k: String) = if (isNull(k)) null else getString(k)
        fun objects(k: String): List<JSONObject> {
            val a = root.getJSONArray(k)
            require(a.length() <= 100000)
            return (0 until a.length()).map { a.getJSONObject(it) }
        }
        val inputProfiles = if (version >= 3) objects("pump_profiles").map { j ->
            require(j.get("archived") is Boolean) { "Invalid archived flag" }
            PumpProfile(id = j.getString("id"), type = PumpType.valueOf(j.getString("type")),
                nickname = j.getString("nickname"), customType = j.getString("customType"),
                brand = j.getString("brand"), model = j.getString("model"), notes = j.getString("notes"),
                archived = j.getBoolean("archived")).also(::validatePumpProfile)
        } else emptyList()
        require(inputProfiles.map { it.id }.distinct().size == inputProfiles.size) { "Duplicate pump IDs" }
        require(inputProfiles.map { it.nickname.lowercase(java.util.Locale.ROOT) }.distinct().size == inputProfiles.size) { "Duplicate pump nicknames" }
        val inputSessions =
            objects("sessions").map { j ->
                Session(
                    j.getString("id"),
                    j.getLong("start"),
                    j.getLong("end"),
                    j.getLong("activeSeconds"),
                    j.getLong("volume"),
                    j.nullLong("left"),
                    j.nullLong("right"),
                    j.getString("notes"),
                    j.getString("mode"),
                    j.getLong("created"),
                    j.getLong("updated"),
                    if (version == 1) BreastfeedingStatus.UNKNOWN
                    else BreastfeedingStatus.valueOf(j.getString("breastfedBefore")),
                    if (version >= 3) j.nullString("pumpProfileId") else null,
                )
            }
        val inputBags =
            objects("bags").map { j ->
                Bag(
                    j.getString("id"),
                    j.getInt("number"),
                    j.getLong("stored"),
                    j.getLong("initial"),
                    j.getString("location"),
                    j.getLong("expires"),
                    j.getString("notes"),
                    j.nullString("sourceSession"),
                )
            }
        val inputMoves =
            objects("movements").map { j ->
                Movement(
                    j.getString("id"),
                    j.getString("bagId"),
                    j.getLong("time"),
                    j.getString("kind"),
                    j.getLong("delta"),
                    j.getString("notes"),
                    j.nullString("oldLocation"),
                    j.nullString("newLocation"),
                )
            }
        val inputLegacyRecords =
            if (root.has("legacy_records"))
                objects("legacy_records").map { j ->
                    val kind = j.getString("kind")
                    require(kind in listOf("pump", "stash")) { "Invalid retained legacy record." }
                    val id = LegacyFormat.id(j.get("id"))
                    val raw = JSONObject(j.getString("json"))
                    require(raw.getString("id") == id)
                    JSONObject(j.getString("projection"))
                    java.time.ZoneId.of(j.getString("zone"))
                    LegacyRecord(
                        kind,
                        id,
                        raw.toString(),
                        j.getString("projection"),
                        j.getString("zone"),
                    )
                }
            else emptyList()
        require(
            inputLegacyRecords.map { it.kind to it.id }.distinct().size == inputLegacyRecords.size
        ) {
            "Duplicate retained legacy IDs."
        }
        val legacyMetadata =
            if (root.has("legacy_metadata"))
                objects("legacy_metadata").map { j ->
                    val id = j.getString("id")
                    val json = j.getString("json")
                    require(
                        id == "latest" || id.startsWith("root:") || id.startsWith("bag-number:")
                    ) {
                        "Invalid legacy metadata."
                    }
                    if (id.startsWith("bag-number:")) {
                        val number = id.removePrefix("bag-number:").toIntOrNull()
                        require(
                            number != null &&
                                number in 1 until Int.MAX_VALUE &&
                                json == number.toString()
                        ) {
                            "Invalid reserved legacy bag number."
                        }
                    } else if (id != "latest") {
                        val meta = JSONObject(json)
                        JSONObject(meta.getJSONObject("settingsSnapshot").toString())
                        LegacyFormat.validateRoot(meta.getJSONObject("root"))
                    }
                    LegacyMetadata(id, json)
                }
            else emptyList()
        require(legacyMetadata.map { it.id }.distinct().size == legacyMetadata.size) {
            "Duplicate legacy metadata."
        }
        legacyMetadata
            .find { it.id == "latest" }
            ?.let { latest ->
                require(legacyMetadata.any { it.id == "root:${latest.json}" }) {
                    "Missing legacy export metadata."
                }
            }
        val legacyPreferences =
            root.optJSONObject("legacy_preferences")?.let { j ->
                val prefs = JSONObject(j.getString("json"))
                validateLegacyPreferences(prefs)
                LegacyPreferences(token = j.getString("token"), json = prefs.toString())
            }
        val preferencesSnapshot =
            root.optJSONObject("preferences_snapshot")?.also { validateLegacyPreferences(it) }
        val preferenceChanges =
            if (applyLegacySettings) {
                val incoming = preferencesSnapshot ?: legacyPreferences?.let { JSONObject(it.json) }
                incoming?.let { mergeLegacyPreferences(settings.settings.first(), it) }
            } else null
        val incomingDeletions =
            if (root.has("deletions"))
                objects("deletions").map { j ->
                    val kind = j.getString("kind")
                    require(
                        kind in
                            listOf(
                                "session",
                                "bag",
                                "movement",
                                "legacy-stash",
                                "session-allocation",
                            )
                    ) {
                        "Invalid deletion marker."
                    }
                    val id = j.get("id")
                    require(id is String && id.isNotBlank() && id.length <= 512) {
                        "Invalid deletion marker ID."
                    }
                    DeletionMarker(kind, id)
                }
            else emptyList()
        inputSessions.forEach(::validateSession)
        require(inputSessions.all { s -> s.pumpProfileId == null || inputProfiles.any { it.id == s.pumpProfileId } }) {
            "Missing pump profile in backup"
        }
        repo.db.withTransaction {
            val incomingState = DeletedRecords(incomingDeletions)
            require(
                repo.dao.allSessions().none { incomingState.session(it.id) } &&
                    repo.dao.allBags().none { incomingState.bag(it.id) } &&
                    repo.dao.allMovements().none { incomingState.movement(it.id) } &&
                    repo.dao.legacyRecords().none { incomingState.record(it) }
            ) {
                "This backup contains deletion markers that conflict with retained records. Merge changed nothing."
            }
            val state = DeletedRecords(repo.dao.deletions() + incomingDeletions)
            val sessions = inputSessions.filterNot { state.session(it.id) }
            val bags =
                inputBags
                    .filterNot { state.bag(it.id) }
                    .map { b ->
                        if (b.sourceSession?.let { state.session(it) } == true)
                            b.copy(sourceSession = null)
                        else b
                    }
            val moves = inputMoves.filterNot { state.bag(it.bagId) || state.movement(it.id) }
            val legacyRecords =
                inputLegacyRecords
                    .filterNot { state.record(it) }
                    .map { r ->
                        if (r.kind == "stash" && r.projection != "{}") {
                            val p = JSONObject(r.projection)
                            val source = p.opt("sourceSession")
                            if (source is String && state.session(source)) {
                                p.put("sourceSession", JSONObject.NULL)
                                r.copy(projection = p.toString())
                            } else r
                        } else r
                    }
            sessions.forEach(::validateSession)
            require(
                sessions.map { it.id }.distinct().size == sessions.size &&
                    bags.map { it.id }.distinct().size == bags.size &&
                    moves.map { it.id }.distinct().size == moves.size
            ) {
                "Duplicate backup IDs"
            }
            require(bags.map { it.number }.distinct().size == bags.size) { "Duplicate bag numbers" }
            bags.forEach { b ->
                require(
                    b.number > 0 &&
                        b.initial in 1..Volume.MAX &&
                        b.expires >= b.stored &&
                        b.location in listOf("Fridge", "Freezer", "Deep Freezer")
                ) {
                    "Invalid bag"
                }
                require(b.sourceSession == null || sessions.any { it.id == b.sourceSession }) {
                    "Missing source session"
                }
                val ledger = moves.filter { it.bagId == b.id }
                require(
                    ledger.count { it.kind == "ADD" } == 1 &&
                        ledger.first { it.kind == "ADD" }.delta == b.initial
                ) {
                    "Invalid addition ledger"
                }
                var remaining = 0L
                ledger
                    .sortedWith(
                        compareBy<Movement> { it.time }.thenBy { if (it.kind == "ADD") 0 else 1 }
                    )
                    .forEach { m ->
                        require(
                            m.time >= b.stored &&
                                when (m.kind) {
                                    "ADD" -> m.delta > 0
                                    "TRANSFER" -> m.delta == 0L
                                    "Feeding",
                                    "Donation",
                                    "Other",
                                    "Discard" -> m.delta < 0
                                    else -> false
                                }
                        ) {
                            "Invalid inventory transaction"
                        }
                        remaining = Math.addExact(remaining, m.delta)
                        require(remaining in 0..b.initial) { "Invalid inventory balance" }
                    }
            }
            require(moves.all { m -> bags.any { it.id == m.bagId } }) { "Missing bag" }
            sessions.forEach { s ->
                require(bags.filter { it.sourceSession == s.id }.sumOf { it.initial } <= s.volume) {
                    "Allocations exceed output"
                }
            }
            val oldProfiles = repo.dao.allPumpProfiles().associateBy { it.id }
            require(inputProfiles.all { oldProfiles[it.id] == null || oldProfiles[it.id] == it }) { "Pump profile conflicts with existing records" }
            require(inputProfiles.all { p -> oldProfiles.values.none { it.id != p.id && it.nickname.equals(p.nickname, ignoreCase = true) } }) { "Pump nickname conflicts with existing records" }
            val oldS = repo.dao.allSessions().associateBy { it.id }
            val oldB = repo.dao.allBags().associateBy { it.id }
            val oldM = repo.dao.allMovements().associateBy { it.id }
            require(
                sessions.all { oldS[it.id] == null || oldS[it.id] == it } &&
                    bags.all { oldB[it.id] == null || oldB[it.id] == it } &&
                    moves.all { oldM[it.id] == null || oldM[it.id] == it }
            ) {
                "Backup conflicts with existing records; import into an empty installation or use a matching backup"
            }
            val unionMoves = oldM.values.toList() + moves.filter { it.id !in oldM }
            val unionBags = oldB.values.toList() + bags.filter { it.id !in oldB }
            unionBags.forEach { b ->
                var balance = 0L
                unionMoves
                    .filter { it.bagId == b.id }
                    .sortedWith(
                        compareBy<Movement> { it.time }.thenBy { if (it.kind == "ADD") 0 else 1 }
                    )
                    .forEach { m ->
                        balance = Math.addExact(balance, m.delta)
                        require(balance in 0..b.initial) { "Merged backup would overdraw a bag" }
                    }
            }
            (oldS.values.toList() + sessions.filter { it.id !in oldS }).forEach { s ->
                require(
                    unionBags.filter { it.sourceSession == s.id }.sumOf { it.initial } <= s.volume
                ) {
                    "Merged allocations exceed pump output"
                }
            }
            require(bags.all { b -> oldB.values.none { it.number == b.number && it.id != b.id } }) {
                "Bag number conflicts with existing inventory"
            }
            inputProfiles.filter { it.id !in oldProfiles }.forEach { repo.dao.insertPumpProfile(it) }
            sessions.filter { it.id !in oldS }.forEach { repo.dao.insertSession(it) }
            bags.filter { it.id !in oldB }.forEach { repo.dao.insertBag(it) }
            moves.filter { it.id !in oldM }.forEach { repo.dao.insertMovement(it) }
            val oldLegacy = repo.dao.legacyRecords().associateBy { it.kind to it.id }
            require(
                legacyRecords.all { r ->
                    oldLegacy[r.kind to r.id]?.let { state.equivalent(it, r) } ?: true
                }
            ) {
                "Retained legacy records conflict. Nothing was imported."
            }
            legacyRecords.forEach { repo.dao.putLegacyRecord(it) }
            legacyMetadata.forEach { repo.dao.putLegacyMetadata(it) }
            incomingDeletions.forEach { repo.dao.markDeleted(it) }
            repo.dao.reconcileDeletedLinks()
            repo.dao.synchronizeLegacyReservations()
            if (preferenceChanges != null)
                repo.dao.putLegacyPreferences(
                    LegacyPreferences(token = newId(), json = preferenceChanges.toString())
                )
        }
    }
}
