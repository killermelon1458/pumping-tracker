package app.pumping.data

import androidx.room.Entity
import java.math.BigDecimal
import org.json.JSONObject

/** IDs only: permanent record contents are removed, while merges remember the user's deletion. */
@Entity(tableName = "deletions", primaryKeys = ["kind", "id"])
data class DeletionMarker(val kind: String, val id: String)

internal class DeletedRecords(markers: List<DeletionMarker>) {
    private val keys = markers.map { it.kind to it.id }.toSet()

    fun contains(kind: String, id: String) = kind to id in keys

    fun session(id: String) = contains("session", id)

    fun bag(id: String) = contains("bag", id)

    fun movement(id: String) = contains("movement", id)

    fun stash(id: String) = contains("legacy-stash", id) || bag("legacy:bag:$id")

    fun record(r: LegacyRecord) =
        when (r.kind) {
            "pump" -> session(r.id)
            else -> stash(r.id)
        }

    fun equivalent(a: LegacyRecord, b: LegacyRecord): Boolean {
        if (a.kind == "pump" && contains("session-allocation", a.id)) {
            fun withoutAllocations(text: String) =
                JSONObject(text).apply {
                    listOf("stashAmount", "stashBagId", "stashBagIds").forEach(::remove)
                }
            return LegacyFormat.equivalent(
                withoutAllocations(a.json).toString(),
                withoutAllocations(b.json).toString(),
            )
        }
        return LegacyFormat.equivalent(a.json, b.json)
    }
}

enum class DeleteTarget {
    SESSION,
    BAG,
}

/** Immutable review snapshot; execution rejects changed links, amounts or transactions. */
data class DeletionPlan(
    val target: DeleteTarget,
    val id: String,
    val session: Session?,
    val bags: List<Bag>,
    val movements: List<Movement>,
) {
    fun deletedSession(includeRelated: Boolean): Session? =
        if (target == DeleteTarget.SESSION || includeRelated) session else null

    fun deletedBags(includeRelated: Boolean): List<Bag> =
        if (target == DeleteTarget.SESSION) {
            if (includeRelated) bags else emptyList()
        } else bags.filter { it.id == id }

    fun remaining(bag: Bag) = movements.filter { it.bagId == bag.id }.sumOf { it.delta }

    fun removedMovements(includeRelated: Boolean): List<Movement> {
        val ids = deletedBags(includeRelated).map { it.id }.toSet()
        return movements.filter { it.bagId in ids }
    }
}

internal suspend fun PumpDao.synchronizeLegacyReservations() {
    clearBagNumberReservations()
    val deleted = DeletedRecords(deletions())
    legacyRecords()
        .filter { it.kind == "stash" && !deleted.record(it) }
        .map { JSONObject(it.json).getInt("bagId") }
        .toSet()
        .forEach { number ->
            putLegacyMetadata(LegacyMetadata("bag-number:$number", number.toString()))
        }
}

/** Strip revoked allocations by stable session ID, never by a newly reused visible bag number. */
internal suspend fun PumpDao.reconcileDeletedLinks() {
    val markers = deletions()
    val state = DeletedRecords(markers)
    val sessions = allSessions().associateBy { it.id }
    val bags = allBags().associateBy { it.id }
    val raw = legacyRecords()
    val liveStash = raw.filter { it.kind == "stash" && !state.record(it) }
    val rawStash = liveStash.associateBy { it.id }
    val sourceNumbers = liveStash.map { JSONObject(it.json).getInt("bagId") }.toSet()
    val linked = bags.values.groupBy { it.sourceSession }
    for (r in raw) {
        if (r.kind == "pump" && r.id !in sessions) {
            // Old native JSON backups used a retained raw pump ID as the deletion marker.
            markDeleted(DeletionMarker("session", r.id))
            deleteLegacyRecord(r.kind, r.id)
            continue
        }
        if (state.record(r)) {
            deleteLegacyRecord(r.kind, r.id)
            continue
        }
        val rawPump = if (r.kind == "pump") JSONObject(r.json) else null
        val dangling =
            rawPump != null &&
                !rawPump.isNull("stashBagId") &&
                rawPump.getInt("stashBagId") !in sourceNumbers &&
                linked[r.id].orEmpty().none { it.number == rawPump.getInt("stashBagId") }
        if (
            r.kind == "pump" &&
                r.id in sessions &&
                (state.contains("session-allocation", r.id) || dangling)
        ) {
            if (dangling) markDeleted(DeletionMarker("session-allocation", r.id))
            val kept = linked[r.id].orEmpty().sortedBy { it.number }
            val j = JSONObject(r.json)
            val exactAmount =
                kept.fold(BigDecimal.ZERO) { total, b ->
                    val source = rawStash[b.id.removePrefix("legacy:bag:")]
                    val original = source?.let { JSONObject(it.json) }
                    val projection = source?.let { JSONObject(it.projection) }
                    val quantity =
                        if (original != null && projection?.optLong("initial", -1) == b.initial)
                            LegacyFormat.number(original.get("volume"))
                        else BigDecimal(b.initial).movePointLeft(3)
                    total + quantity
                }
            j.put("stashAmount", exactAmount)
                .put("stashBagId", kept.singleOrNull()?.number ?: JSONObject.NULL)
                .put("stashBagIds", JSONObject.NULL)
            putLegacyRecord(r.copy(json = j.toString()))
        } else if (r.kind == "stash" && r.projection != "{}") {
            val projection = JSONObject(r.projection)
            val b = bags[projection.optString("id")]
            if (
                b != null &&
                    projection.opt("sourceSession")?.toString() !=
                        (b.sourceSession ?: JSONObject.NULL).toString()
            ) {
                projection.put("sourceSession", b.sourceSession ?: JSONObject.NULL)
                putLegacyRecord(r.copy(projection = projection.toString()))
            }
        }
    }
}
