package app.pumping.data

import androidx.room.*

enum class PowerPumpStatus { NORMAL, POWER, UNKNOWN }
enum class PhaseKind { PUMP, REST }

/** Boundaries are derived contiguously from parent.start + preceding durations, never guessed gaps. */
@Entity(tableName = "session_phases", indices = [Index(value = ["sessionId", "position"], unique = true)],
    foreignKeys = [ForeignKey(entity = Session::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class SessionPhase(
    @PrimaryKey val id: String = newId(),
    val sessionId: String,
    val position: Int,
    val kind: PhaseKind,
    val seconds: Long,
)
