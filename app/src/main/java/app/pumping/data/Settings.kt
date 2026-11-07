package app.pumping.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import app.pumping.domain.PumpReference
import app.pumping.domain.TimerState
import kotlinx.coroutines.flow.*
import org.json.JSONObject

private val Context.pumpStore by preferencesDataStore("pumping_settings")

data class Settings(
    val inputUnit: String = "oz",
    val displayUnit: String = "oz",
    val clock24: Boolean = false,
    val dark: Boolean = true,
    val intervals: Boolean = true,
    val deep: Boolean = false,
    val fridgeDays: Int = 4,
    val freezerDays: Int = 180,
    val deepDays: Int = 365,
    val reminderMinutes: Int = 180,
    val nextPump: Long = 0,
    val pumpReference: PumpReference = PumpReference.START,
    val morningHour: Int = 7,
    val child: String = "",
    val goalStart: String = "",
    val goalEnd: String = "",
    val partsAt: Long = 0,
    val partsDays: Int = 30,
    val expiryAlerts: Boolean = false,
    val lastExpiryAlert: Long = 0,
    val savingsPerLiter: String = "0",
) {
    fun days(location: String) =
        when (location) {
            "Fridge" -> fridgeDays
            "Freezer" -> freezerDays
            else -> deepDays
        }
}

class SettingsStore(
    context: Context,
    private val dao: PumpDao? = null,
    isolatedStore: DataStore<Preferences>? = null,
) {
    private val store = isolatedStore ?: context.pumpStore
    val settings =
        combine(store.data, dao?.legacyPreferences() ?: flowOf(null)) { stored, imported ->
            val p = stored.toMutablePreferences()
            if (imported != null) {
                val values = normalizedUnitPreferences(JSONObject(imported.json))
                values.keys().forEach { key ->
                    val ack =
                        stored[stringPreferencesKey("legacy-ack-$key")]
                            ?: if (
                                key in listOf("inputUnit", "displayUnit") &&
                                    JSONObject(imported.json).has("unit")
                            )
                                stored[stringPreferencesKey("legacy-ack-unit")]
                            else null
                    if (ack != imported.token) {
                        when (val value = values.get(key)) {
                            is Boolean -> p[booleanPreferencesKey(key)] = value
                            is Int -> p[intPreferencesKey(key)] = value
                            is String -> p[stringPreferencesKey(key)] = value
                        }
                    }
                }
            }
            Settings(
                inputUnit =
                    p[stringPreferencesKey("inputUnit")] ?: p[stringPreferencesKey("unit")] ?: "oz",
                displayUnit =
                    p[stringPreferencesKey("displayUnit")]
                        ?: p[stringPreferencesKey("unit")]
                        ?: "oz",
                clock24 = p[booleanPreferencesKey("24h")] ?: false,
                dark = p[booleanPreferencesKey("dark")] ?: true,
                intervals = p[booleanPreferencesKey("intervals")] ?: true,
                deep = p[booleanPreferencesKey("deep")] ?: false,
                fridgeDays = p[intPreferencesKey("fridge")] ?: 4,
                freezerDays = p[intPreferencesKey("freezer")] ?: 180,
                deepDays = p[intPreferencesKey("deepDays")] ?: 365,
                reminderMinutes = p[intPreferencesKey("reminderMinutes")] ?: 180,
                nextPump = p[longPreferencesKey("nextPump")] ?: 0,
                pumpReference = PumpReference.from(p[stringPreferencesKey("pumpReference")]),
                morningHour = p[intPreferencesKey("morningHour")] ?: 7,
                child = p[stringPreferencesKey("child")] ?: "",
                goalStart = p[stringPreferencesKey("goalStart")] ?: "",
                goalEnd = p[stringPreferencesKey("goalEnd")] ?: "",
                partsAt = p[longPreferencesKey("partsAt")] ?: 0,
                partsDays = p[intPreferencesKey("partsDays")] ?: 30,
                expiryAlerts = p[booleanPreferencesKey("expiryAlerts")] ?: false,
                lastExpiryAlert = p[longPreferencesKey("lastExpiryAlert")] ?: 0,
                savingsPerLiter = p[stringPreferencesKey("savings")] ?: "0",
            )
        }
    val pumpAlarm =
        store.data.map { p ->
            val due = p[longPreferencesKey("nextPump")] ?: 0
            PumpAlarmState(
                enabled = p[booleanPreferencesKey("pumpEnabled")] ?: (due > 0),
                due = due,
                token = p[stringPreferencesKey("pumpToken")] ?: "",
                basis = p[stringPreferencesKey("pumpBasis")] ?: "",
                snoozeAt = p[longPreferencesKey("pumpSnoozeAt")] ?: 0,
                snoozeToken = p[stringPreferencesKey("pumpSnoozeToken")] ?: "",
                visibleToken = p[stringPreferencesKey("pumpVisibleToken")] ?: "",
            )
        }

    suspend fun savePumpAlarm(state: PumpAlarmState) {
        store.edit { p ->
            p[booleanPreferencesKey("pumpEnabled")] = state.enabled
            p[longPreferencesKey("nextPump")] = state.due
            p[stringPreferencesKey("pumpToken")] = state.token
            p[stringPreferencesKey("pumpBasis")] = state.basis
            p[longPreferencesKey("pumpSnoozeAt")] = state.snoozeAt
            p[stringPreferencesKey("pumpSnoozeToken")] = state.snoozeToken
            p[stringPreferencesKey("pumpVisibleToken")] = state.visibleToken
        }
    }

    suspend fun reminderOptions(minutes: Int, reference: PumpReference, morning: Int) {
        require(minutes in 1..10080 && morning in 0..23)
        store.edit {
            it[intPreferencesKey("reminderMinutes")] = minutes
            it[stringPreferencesKey("pumpReference")] = reference.name
            it[intPreferencesKey("morningHour")] = morning
        }
    }

    val timer =
        store.data.map { p ->
            TimerState(
                id = p[stringPreferencesKey("timerId")] ?: "",
                start = p[longPreferencesKey("timerStart")] ?: 0,
                accumulated = p[longPreferencesKey("timerAccumulated")] ?: 0,
                tick = p[longPreferencesKey("timerTick")] ?: 0,
                boot = p[intPreferencesKey("timerBoot")] ?: -1,
                running = p[booleanPreferencesKey("timerRunning")] ?: false,
                targetMinutes = p[intPreferencesKey("timerTarget")] ?: 15,
                draftEnd = p[longPreferencesKey("timerEnd")] ?: 0,
            )
        }

    suspend fun string(key: String, value: String) {
        val token = dao?.currentLegacyPreferences()?.token
        val keys = if (key == "unit") listOf("inputUnit", "displayUnit") else listOf(key)
        if (keys.any { it in listOf("inputUnit", "displayUnit") })
            require(value in listOf("oz", "mL"))
        store.edit { p ->
            // Persist the old single-unit default into both keys before either is changed.
            val previous = p[stringPreferencesKey("unit")] ?: "oz"
            for (k in listOf("inputUnit", "displayUnit")) {
                if (p[stringPreferencesKey(k)] == null) p[stringPreferencesKey(k)] = previous
            }
            keys.forEach { k ->
                p[stringPreferencesKey(k)] = value
                if (token != null) p[stringPreferencesKey("legacy-ack-$k")] = token
            }
        }
    }

    suspend fun bool(key: String, value: Boolean) {
        val token = dao?.currentLegacyPreferences()?.token
        store.edit {
            it[booleanPreferencesKey(key)] = value
            if (token != null) it[stringPreferencesKey("legacy-ack-$key")] = token
        }
    }

    suspend fun int(key: String, value: Int) {
        val token = dao?.currentLegacyPreferences()?.token
        store.edit {
            it[intPreferencesKey(key)] = value
            if (token != null) it[stringPreferencesKey("legacy-ack-$key")] = token
        }
    }

    suspend fun long(key: String, value: Long) {
        store.edit { it[longPreferencesKey(key)] = value }
    }

    suspend fun deliveryHandled(kind: String, identity: String): Boolean =
        store.data.first()[stringPreferencesKey("delivered-$kind")] == identity

    suspend fun claimDelivery(kind: String, identity: String): Boolean {
        var claimed = false
        store.edit { p ->
            val key = stringPreferencesKey("delivered-$kind")
            if (p[key] != identity) {
                p[key] = identity
                claimed = true
            }
        }
        return claimed
    }

    suspend fun saveTimer(t: TimerState) {
        store.edit { p ->
            p[stringPreferencesKey("timerId")] = t.id
            p[longPreferencesKey("timerStart")] = t.start
            p[longPreferencesKey("timerAccumulated")] = t.accumulated
            p[longPreferencesKey("timerTick")] = t.tick
            p[intPreferencesKey("timerBoot")] = t.boot
            p[booleanPreferencesKey("timerRunning")] = t.running
            p[intPreferencesKey("timerTarget")] = t.targetMinutes
            p[longPreferencesKey("timerEnd")] = t.draftEnd
        }
    }
}
