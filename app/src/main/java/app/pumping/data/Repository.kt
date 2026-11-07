package app.pumping.data

import androidx.room.withTransaction
import app.pumping.domain.*

data class Allocation(val volume: Long, val location: String, val days: Int, val notes: String = "")

class Repository(val db: PumpDatabase) {
    val dao = db.dao()

    suspend fun saveSession(s: Session, bags: List<Allocation> = emptyList()) =
        db.withTransaction {
            require(!dao.isDeleted("session", s.id)) {
                "This session was deleted. Create a new log instead."
            }
            validateSession(s)
            require(bags.all { it.volume > 0 }) { "Every bag needs a positive quantity" }
            val existing = dao.session(s.id)
            val allocated = dao.allocated(s.id)
            require(allocated + bags.sumOf { it.volume } <= s.volume) {
                "Stored allocations exceed measured output. Existing bags retain their recorded quantities."
            }
            if (existing == null) dao.insertSession(s) else dao.updateSession(s)
            for (a in bags) addBagInternal(a, s.end, s.id)
        }

    suspend fun deletionPlan(target: DeleteTarget, id: String): DeletionPlan =
        db.withTransaction {
            val selected =
                if (target == DeleteTarget.BAG)
                    requireNotNull(dao.bag(id)) { "This bag was already deleted." }
                else null
            val session =
                if (target == DeleteTarget.SESSION)
                    requireNotNull(dao.session(id)) { "This session was already deleted." }
                else selected?.sourceSession?.let { dao.session(it) }
            val bags =
                (session?.let { dao.linkedBags(it.id) }.orEmpty() + listOfNotNull(selected))
                    .distinctBy { it.id }
                    .sortedWith(compareBy<Bag> { it.number }.thenBy { it.id })
            val ids = bags.map { it.id }.toSet()
            DeletionPlan(
                target,
                id,
                session,
                bags,
                dao.allMovements()
                    .filter { it.bagId in ids }
                    .sortedWith(compareBy<Movement> { it.time }.thenBy { it.id }),
            )
        }

    suspend fun delete(plan: DeletionPlan, includeRelated: Boolean = false) =
        db.withTransaction {
            require(deletionPlan(plan.target, plan.id) == plan) {
                "These records changed. Refresh the deletion details and review them again."
            }
            val removed = plan.deletedBags(includeRelated)
            val raw = dao.legacyRecords()
            val affected = removed.mapNotNull { it.sourceSession }.toMutableSet()
            for (b in removed) {
                val known =
                    raw.filter {
                        it.kind == "stash" &&
                            it.projection != "{}" &&
                            org.json.JSONObject(it.projection).optString("id") == b.id
                    }
                val sourceNumbers =
                    known.map { org.json.JSONObject(it.json).getInt("bagId") }.toSet()
                raw.filter { it.kind == "pump" }
                    .forEach { r ->
                        val j = org.json.JSONObject(r.json)
                        if (!j.isNull("stashBagId") && j.optInt("stashBagId") in sourceNumbers)
                            affected += r.id
                    }
                val sourceRecords =
                    raw.filter {
                        it.kind == "stash" &&
                            (it in known ||
                                (org.json.JSONObject(it.json).optInt("bagId", -1) in sourceNumbers))
                    }
                sourceRecords.forEach {
                    dao.markDeleted(DeletionMarker("legacy-stash", it.id))
                    dao.deleteLegacyRecord(it.kind, it.id)
                }
                // Stable bag IDs and movement IDs remain suppressed even after their display number
                // is reused.
                dao.markDeleted(DeletionMarker("bag", b.id))
                plan.movements
                    .filter { it.bagId == b.id }
                    .forEach { dao.markDeleted(DeletionMarker("movement", it.id)) }
                dao.deleteBagMovements(b.id)
                dao.deleteBag(b.id)
            }
            affected.forEach { dao.markDeleted(DeletionMarker("session-allocation", it)) }
            plan.deletedSession(includeRelated)?.let { s ->
                dao.markDeleted(DeletionMarker("session", s.id))
                dao.deleteLegacyRecord("pump", s.id)
                dao.deleteSession(s.id)
            }
            dao.reconcileDeletedLinks()
            dao.synchronizeLegacyReservations()
        }

    suspend fun deleteSession(id: String, deleteLinkedBags: Boolean = false) =
        db.withTransaction { delete(deletionPlan(DeleteTarget.SESSION, id), deleteLinkedBags) }

    suspend fun deleteBag(id: String, deleteOriginatingSession: Boolean = false) =
        db.withTransaction { delete(deletionPlan(DeleteTarget.BAG, id), deleteOriginatingSession) }

    suspend fun addBag(a: Allocation, time: Long) =
        db.withTransaction { addBagInternal(a, time, null) }

    private suspend fun addBagInternal(a: Allocation, time: Long, source: String?) {
        require(a.volume in 1..Volume.MAX && a.days in 1..3650) {
            "Invalid bag quantity or storage duration"
        }
        require(a.location in listOf("Fridge", "Freezer", "Deep Freezer")) {
            "Invalid storage location"
        }
        val nextNumber = dao.nextNumber()
        require(nextNumber > 0) { "No valid bag number is available." }
        val bag =
            Bag(
                number = nextNumber,
                stored = time,
                initial = a.volume,
                location = a.location,
                expires = expiry(time, a.days),
                notes = a.notes,
                sourceSession = source,
            )
        dao.insertBag(bag)
        dao.insertMovement(
            Movement(bagId = bag.id, time = time, kind = "ADD", delta = a.volume, notes = a.notes)
        )
    }

    suspend fun useBag(id: String, amount: Long, reason: String, time: Long, notes: String) =
        db.withTransaction {
            val bag = requireNotNull(dao.bag(id)) { "Bag not found" }
            require(reason in listOf("Feeding", "Donation", "Other", "Discard")) {
                "Choose a usage reason"
            }
            require(time >= bag.stored) { "Usage cannot be before storage" }
            require(amount > 0 && amount <= dao.remaining(id)) {
                "Quantity exceeds the remaining milk"
            }
            dao.insertMovement(
                Movement(bagId = id, time = time, kind = reason, delta = -amount, notes = notes)
            )
        }

    suspend fun transfer(id: String, location: String, days: Int, now: Long) =
        db.withTransaction {
            val b = requireNotNull(dao.bag(id))
            require(location in listOf("Fridge", "Freezer", "Deep Freezer") && days in 1..3650)
            require(dao.remaining(id) > 0)
            // Moving never extends an existing estimate.
            dao.updateBag(
                b.copy(location = location, expires = minOf(b.expires, expiry(now, days)))
            )
            dao.insertMovement(
                Movement(
                    bagId = id,
                    time = now,
                    kind = "TRANSFER",
                    delta = 0,
                    oldLocation = b.location,
                    newLocation = location,
                )
            )
        }
}
