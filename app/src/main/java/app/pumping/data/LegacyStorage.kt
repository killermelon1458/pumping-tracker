package app.pumping.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Original source data is retained locally, including sub-microliter amounts and microseconds. */
@Entity(tableName = "legacy_records", primaryKeys = ["kind", "id"])
data class LegacyRecord(
    val kind: String,
    val id: String,
    val json: String,
    val projection: String,
    val zone: String,
)

@Entity(tableName = "legacy_metadata")
data class LegacyMetadata(@PrimaryKey val id: String, val json: String)

/** Settings overlay commits with records, avoiding a non-atomic Room/DataStore dual write. */
@Entity(tableName = "legacy_preferences")
data class LegacyPreferences(@PrimaryKey val id: Int = 1, val token: String, val json: String)

val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS legacy_records (kind TEXT NOT NULL, id TEXT NOT NULL, json TEXT NOT NULL, projection TEXT NOT NULL, zone TEXT NOT NULL, PRIMARY KEY(kind, id))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS legacy_metadata (id TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS legacy_preferences (id INTEGER NOT NULL PRIMARY KEY, token TEXT NOT NULL, json TEXT NOT NULL)"
            )
        }
    }

internal fun validateLegacyPreferences(prefs: org.json.JSONObject) {
    prefs.keys().forEach { k ->
        when (k) {
            "unit",
            "inputUnit",
            "displayUnit" -> require(prefs.get(k) in listOf("oz", "mL"))
            "dark",
            "deep" -> require(prefs.get(k) is Boolean)
            "fridge",
            "freezer",
            "deepDays" -> app.pumping.data.LegacyFormat.integer(prefs.get(k), 1, 3650)
            "savings" ->
                require(
                    prefs.get(k) is String &&
                        prefs.getString(k).toBigDecimalOrNull()?.let {
                            it >= java.math.BigDecimal.ZERO
                        } == true
                )
            else -> error("Unsupported imported preference.")
        }
    }
}

/** A partial legacy settings object must not reset preferences supplied by an earlier import. */
internal fun mergeLegacyPreferences(
    current: Settings,
    incoming: org.json.JSONObject,
): org.json.JSONObject =
    org.json
        .JSONObject()
        .put("inputUnit", current.inputUnit)
        .put("displayUnit", current.displayUnit)
        .put("dark", current.dark)
        .put("deep", current.deep)
        .put("fridge", current.fridgeDays)
        .put("freezer", current.freezerDays)
        .put("deepDays", current.deepDays)
        .put("savings", current.savingsPerLiter)
        .apply {
            normalizedUnitPreferences(incoming).let { values ->
                values.keys().forEach { key -> put(key, values.get(key)) }
            }
        }

val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS deletions (kind TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY(kind,id))"
            )
            // Version 2 kept raw source IDs as implicit deletion markers when a pump was removed.
            db.execSQL(
                "INSERT OR IGNORE INTO deletions(kind,id) SELECT 'session', id FROM legacy_records WHERE kind = 'pump' AND id NOT IN (SELECT id FROM sessions)"
            )
            db.execSQL(
                "DELETE FROM legacy_records WHERE kind = 'pump' AND id IN (SELECT id FROM deletions WHERE kind = 'session')"
            )
        }
    }

/** iOS and older Android snapshots have one unit; explicit independent values take priority. */
internal fun normalizedUnitPreferences(incoming: org.json.JSONObject): org.json.JSONObject =
    org.json.JSONObject(incoming.toString()).apply {
        if (has("unit")) {
            if (!has("inputUnit")) put("inputUnit", get("unit"))
            if (!has("displayUnit")) put("displayUnit", get("unit"))
            remove("unit")
        }
    }
