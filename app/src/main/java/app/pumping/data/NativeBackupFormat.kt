package app.pumping.data

import org.json.JSONObject

/** Reject unrecognized schema additions rather than silently losing them during a round trip. */
internal fun validateNativeBackupFields(root: JSONObject, version: Int) {
    fun fields(j: JSONObject, allowed: Set<String>) {
        require(j.keys().asSequence().all { it in allowed }) {
            "Backup contains unsupported fields; use a newer Pumping version. Nothing was imported."
        }
    }
    fields(root, setOf("format", "version", "preferences_snapshot", "deletions", "sessions", "bags",
        "movements", "legacy_records", "legacy_metadata", "legacy_preferences"))
    val sessionFields = setOf("id", "start", "end", "activeSeconds", "volume", "left", "right",
        "notes", "mode", "created", "updated") + if (version == 2) setOf("breastfedBefore") else emptySet()
    val schemas = mapOf(
        "sessions" to sessionFields,
        "bags" to setOf("id", "number", "stored", "initial", "location", "expires", "notes", "sourceSession"),
        "movements" to setOf("id", "bagId", "time", "kind", "delta", "notes", "oldLocation", "newLocation"),
        "deletions" to setOf("kind", "id"),
        "legacy_records" to setOf("kind", "id", "json", "projection", "zone"),
        "legacy_metadata" to setOf("id", "json"),
    )
    schemas.forEach { (key, schema) ->
        if (root.has(key)) {
            val array = root.getJSONArray(key)
            require(array.length() <= 100000) { "Too many backup records" }
            for (i in 0 until array.length()) fields(array.getJSONObject(i), schema)
        }
    }
    root.optJSONObject("legacy_preferences")?.let { fields(it, setOf("id", "token", "json")) }
}
