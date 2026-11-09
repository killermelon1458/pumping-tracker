package app.pumping

import android.app.Application
import androidx.room.Room
import app.pumping.data.*
import app.pumping.reminders.Reminders

open class PumpingApp : Application() {
    open val db by lazy {
        Room.databaseBuilder(this, PumpDatabase::class.java, "pumping.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }
    val repository by lazy { Repository(db) }
    open val settings by lazy { SettingsStore(this, db.dao()) }
    val reminders by lazy { Reminders(this) }

    override fun onCreate() {
        super.onCreate()
        reminders.createChannels()
    }
}
