package app.pumping

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.runner.AndroidJUnitRunner
import app.pumping.data.*
import java.io.File
import java.util.UUID

/** UI tests must never read or replace a user's real database or settings. */
class SyntheticTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        loader: ClassLoader,
        className: String,
        context: Context,
    ): Application =
        super.newApplication(loader, SyntheticTestApplication::class.java.name, context)
}

class SyntheticTestApplication : PumpingApp() {
    override val db by lazy { Room.inMemoryDatabaseBuilder(this, PumpDatabase::class.java).build() }
    override val settings by lazy {
        SettingsStore(
            this,
            db.dao(),
            PreferenceDataStoreFactory.create(
                produceFile = { File(cacheDir, "synthetic-ui-${UUID.randomUUID()}.preferences_pb") }
            ),
        )
    }
}
