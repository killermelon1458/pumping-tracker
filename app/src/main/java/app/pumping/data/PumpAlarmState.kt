package app.pumping.data

/**
 * Durable alarm generation IDs reject already-consumed alarms and obsolete notification actions.
 */
data class PumpAlarmState(
    val enabled: Boolean = false,
    val due: Long = 0,
    val token: String = "",
    val basis: String = "",
    val snoozeAt: Long = 0,
    val snoozeToken: String = "",
    val visibleToken: String = "",
)
