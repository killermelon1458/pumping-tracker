package app.pumping.data

import androidx.room.*

/** Stable categories for later combined statistics predicates; labels are presentation only. */
enum class PumpType(val label: String, val shortLabel: String) {
    STATIONARY("Stationary/Electric", "Stationary"),
    MOBILE_OR_WEARABLE("Wearable/Mobile", "Wearable"),
    MANUAL("Manual/Hand", "Manual"),
    OTHER("Other/Custom", "Other"),
}

@Entity(tableName = "pump_profiles", indices = [Index(value = ["nickname"], unique = true), Index("type")])
data class PumpProfile(
    @PrimaryKey val id: String = newId(),
    val type: PumpType = PumpType.STATIONARY,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val nickname: String,
    val customType: String = "",
    val brand: String = "",
    val model: String = "",
    val notes: String = "",
    val archived: Boolean = false,
)

fun validatePumpProfile(p: PumpProfile) {
    require(p.id.isNotBlank() && p.id.length <= 512) { "Invalid pump ID" }
    require(p.nickname.isNotBlank() && p.nickname == p.nickname.trim() && p.nickname.length <= 80) {
        "Enter a nickname of up to 80 characters"
    }
    require(p.customType.length <= 80 && p.brand.length <= 100 && p.model.length <= 100 && p.notes.length <= 10000) {
        "Pump details are too long"
    }
    require(p.type == PumpType.OTHER || p.customType.isEmpty()) { "Custom type names apply to Other/Custom pumps" }
}

/** Auto names are unique across active and archived pumps; editing never changes an existing name automatically. */
fun suggestedPumpNickname(type: PumpType, brand: String, customType: String, profiles: List<PumpProfile>): String {
    val category = if (type == PumpType.OTHER) customType.trim().ifBlank { type.shortLabel } else type.shortLabel
    val base = listOf(category, brand.trim()).filter { it.isNotBlank() }.joinToString(" ").take(70)
    var candidate = base
    var suffix = 2
    while (profiles.any { it.nickname.equals(candidate, ignoreCase = true) }) candidate = "$base (${suffix++})"
    return candidate
}

/** UI language is intentionally independent of the persisted UNKNOWN enum/backup spelling. */
fun BreastfeedingStatus.displayLabel() = when (this) {
    BreastfeedingStatus.YES -> "Yes"
    BreastfeedingStatus.NO -> "No"
    BreastfeedingStatus.UNKNOWN -> "Not specified"
}
