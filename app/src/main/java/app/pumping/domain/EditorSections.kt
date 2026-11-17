package app.pumping.domain

/** Stable preference/Compose identities. Labels may change without losing a saved layout. */
data class EditorSection(val id: String, val title: String, val required: Boolean = false)

enum class EditorKind(val key: String, val sections: List<EditorSection>) {
    PUMP("pump", listOf(
        EditorSection("timing", "Timing", true),
        EditorSection("power", "Power Pump"),
        EditorSection("breastfeeding", "Breastfeeding"),
        EditorSection("pump", "Pump used"),
        EditorSection("milk", "Milk output", true),
        EditorSection("stash", "Stash It"),
        EditorSection("reminder", "Next Pump"),
        EditorSection("notes", "Notes"),
    )),
    BAG("bag", listOf(
        EditorSection("identity", "Bag number", true),
        EditorSection("timestamp", "Storage date and time", true),
        EditorSection("volume", "Volume", true),
        EditorSection("storage", "Storage location and expiry", true),
        EditorSection("notes", "Notes"),
    )),
    USAGE("usage", listOf(
        EditorSection("bag", "Bag selection", true),
        EditorSection("quantity", "Quantity", true),
        EditorSection("reason", "Usage reason", true),
        EditorSection("timestamp", "Transaction date and time", true),
        EditorSection("notes", "Notes"),
    )),
}

/** Versioned, device-local UI preference, deliberately outside native backup v4. */
data class EditorArrangement(val order: List<String>, val hidden: Set<String> = emptySet()) {
    fun normalized(kind: EditorKind): EditorArrangement {
        val ids = kind.sections.map { it.id }
        return EditorArrangement((order.filter { it in ids } + ids).distinct(),
            hidden.intersect(kind.sections.filterNot { it.required }.map { it.id }.toSet()))
    }
    fun move(id: String, index: Int): EditorArrangement {
        if (id !in order) return this
        val next = order.toMutableList().apply { remove(id); add(index.coerceIn(0,size),id) }
        return copy(order = next)
    }
    fun visible(kind: EditorKind, id: String, show: Boolean): EditorArrangement =
        copy(hidden = if (show) hidden - id else hidden + id).normalized(kind)
    fun encode(): String = "1|${order.joinToString(",")}|${hidden.sorted().joinToString(",")}"
    companion object {
        fun default(kind: EditorKind) = EditorArrangement(kind.sections.map { it.id })
        fun decode(kind: EditorKind, raw: String?): EditorArrangement {
            val parts = raw?.split('|')
            if (parts == null || parts.size != 3 || parts[0] != "1" || raw.length > 4096)
                return default(kind)
            return EditorArrangement(parts[1].split(',').filter { it.isNotEmpty() },
                parts[2].split(',').filter { it.isNotEmpty() }.toSet()).normalized(kind)
        }
    }
}
