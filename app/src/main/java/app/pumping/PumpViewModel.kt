package app.pumping

import android.app.Application
import android.os.SystemClock
import android.provider.Settings.Global
import androidx.lifecycle.*
import app.pumping.data.*
import app.pumping.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PumpViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as PumpingApp
    val repo = app.repository
    val store = app.settings
    val sessions =
        repo.dao
            .sessions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bags =
        repo.dao
            .balances()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val movements =
        repo.dao
            .movements()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val timer = store.timer.stateIn(viewModelScope, SharingStarted.Eagerly, TimerState())
    val error = MutableStateFlow<String?>(null)
    private val mutex = Mutex()
    val boot
        get() = Global.getInt(app.contentResolver, Global.BOOT_COUNT, 0)

    init {
        launch {
            val t = store.timer.first()
            if (t.id.isNotEmpty() && repo.dao.session(t.id) != null)
                store.saveTimer(TimerState(targetMinutes = t.targetMinutes))
            combine(store.settings, repo.dao.sessions(), repo.dao.balances(), store.timer) {
                    s,
                    sessions,
                    bags,
                    timer ->
                    // Observe data edits while alive; delivery itself is owned by AlarmManager.
                    listOf(
                        s.nextPump,
                        s.reminderMinutes,
                        s.pumpReference,
                        s.partsAt,
                        s.expiryAlerts,
                        s.lastExpiryAlert,
                        sessions.map { Triple(it.id, it.start, it.end) },
                        bags.map { Triple(it.bag.id, it.bag.expires, it.remaining) },
                        timer,
                    )
                }
                .distinctUntilChanged()
                .collect { app.reminders.restore(app) }
        }
    }

    fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error.value = e.message ?: "Action could not be completed"
            }
        }
    }

    suspend fun timerAction(action: String, target: Int? = null) =
        mutex.withLock {
            val t = store.timer.first()
            val now = System.currentTimeMillis()
            val tick = SystemClock.elapsedRealtime()
            val elapsed = t.elapsed(tick, boot)
            val next =
                when (action) {
                    "target" -> t.copy(targetMinutes = target ?: 15)
                    "reset" -> TimerState(targetMinutes = t.targetMinutes)
                    "pause" -> t.copy(accumulated = elapsed, running = false, tick = 0)
                    "done" ->
                        t.copy(accumulated = elapsed, running = false, tick = 0, draftEnd = now)
                    else ->
                        if (t.id.isEmpty())
                            TimerState(newId(), now, 0, tick, boot, true, t.targetMinutes)
                        else t.copy(running = true, tick = tick, boot = boot, draftEnd = 0)
                }
            store.saveTimer(next)
            app.reminders.schedule(
                "timer",
                if (next.running)
                    now + maxOf(1000, next.targetMinutes * 60000L - next.elapsed(tick, boot))
                else 0,
            )
        }

    suspend fun save(s: Session, allocations: List<Allocation>, remindAt: Long?) {
        repo.saveSession(s, allocations)
        if (store.timer.first().id == s.id) {
            store.saveTimer(TimerState(targetMinutes = store.timer.first().targetMinutes))
            app.reminders.schedule("timer", 0)
        }
        if (remindAt != null) reminder(remindAt) else app.reminders.restore(app)
    }

    suspend fun reminder(time: Long) {
        app.reminders.setPumpReminder(app, time)
    }
}
