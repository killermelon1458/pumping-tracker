package app.pumping.data

import androidx.room.*
import java.util.UUID
import kotlinx.coroutines.flow.Flow

fun newId(): String = UUID.randomUUID().toString()

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey val id: String = newId(),
    val start: Long,
    val end: Long,
    val activeSeconds: Long,
    val volume: Long,
    val left: Long? = null,
    val right: Long? = null,
    val notes: String = "",
    val mode: String = "MANUAL",
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "bags",
    indices = [Index(value = ["number"], unique = true), Index("sourceSession")],
    foreignKeys =
        [
            ForeignKey(
                entity = Session::class,
                parentColumns = ["id"],
                childColumns = ["sourceSession"],
                onDelete = ForeignKey.SET_NULL,
            )
        ],
)
data class Bag(
    @PrimaryKey val id: String = newId(),
    val number: Int,
    val stored: Long,
    val initial: Long,
    val location: String,
    val expires: Long,
    val notes: String = "",
    val sourceSession: String? = null,
)

@Entity(
    tableName = "movements",
    indices = [Index("bagId")],
    foreignKeys =
        [
            ForeignKey(
                entity = Bag::class,
                parentColumns = ["id"],
                childColumns = ["bagId"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
)
data class Movement(
    @PrimaryKey val id: String = newId(),
    val bagId: String,
    val time: Long,
    val kind: String,
    val delta: Long,
    val notes: String = "",
    val oldLocation: String? = null,
    val newLocation: String? = null,
)

data class BagBalance(@Embedded val bag: Bag, val remaining: Long)

@Dao
interface PumpDao {
    @Query("SELECT * FROM sessions ORDER BY start DESC") fun sessions(): Flow<List<Session>>

    @Query("SELECT * FROM sessions ORDER BY start DESC") suspend fun allSessions(): List<Session>

    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: String): Session?

    @Insert suspend fun insertSession(s: Session)

    @Update suspend fun updateSession(s: Session)

    @Query("SELECT * FROM bags WHERE sourceSession = :sessionId ORDER BY number, id")
    suspend fun linkedBags(sessionId: String): List<Bag>

    @Query("DELETE FROM movements WHERE bagId = :bagId")
    suspend fun deleteBagMovements(bagId: String)

    @Query("DELETE FROM bags WHERE id = :id") suspend fun deleteBag(id: String)

    @Query("DELETE FROM legacy_records WHERE kind = :kind AND id = :id")
    suspend fun deleteLegacyRecord(kind: String, id: String)

    @Query("DELETE FROM legacy_metadata WHERE id LIKE 'bag-number:%'")
    suspend fun clearBagNumberReservations()

    @Query("SELECT * FROM deletions ORDER BY kind, id")
    suspend fun deletions(): List<DeletionMarker>

    @Query("SELECT EXISTS(SELECT 1 FROM deletions WHERE kind = :kind AND id = :id)")
    suspend fun isDeleted(kind: String, id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun markDeleted(marker: DeletionMarker)

    @Query("DELETE FROM deletions") suspend fun clearDeletions()

    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: String)

    @Query(
        "SELECT bags.*, COALESCE(SUM(movements.delta),0) AS remaining FROM bags LEFT JOIN movements ON bags.id = movements.bagId GROUP BY bags.id ORDER BY expires"
    )
    fun balances(): Flow<List<BagBalance>>

    @Query("SELECT * FROM bags ORDER BY number") suspend fun allBags(): List<Bag>

    @Query("SELECT * FROM bags WHERE id = :id") suspend fun bag(id: String): Bag?

    @Query("SELECT COALESCE(SUM(delta),0) FROM movements WHERE bagId = :id")
    suspend fun remaining(id: String): Long

    @Query(
        "SELECT COALESCE(MAX(number),0) + 1 FROM (SELECT number FROM bags UNION ALL SELECT CAST(SUBSTR(id,12) AS INTEGER) AS number FROM legacy_metadata WHERE id LIKE 'bag-number:%')"
    )
    suspend fun nextNumber(): Int

    @Query("SELECT * FROM movements ORDER BY time DESC") fun movements(): Flow<List<Movement>>

    @Query("SELECT * FROM movements ORDER BY time DESC") suspend fun allMovements(): List<Movement>

    @Insert suspend fun insertBag(b: Bag)

    @Update suspend fun updateBag(b: Bag)

    @Insert suspend fun insertMovement(m: Movement)

    @Query("SELECT * FROM legacy_records") suspend fun legacyRecords(): List<LegacyRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLegacyRecord(record: LegacyRecord)

    @Query("SELECT * FROM legacy_metadata") suspend fun legacyMetadata(): List<LegacyMetadata>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLegacyMetadata(record: LegacyMetadata)

    @Query("SELECT * FROM legacy_preferences WHERE id = 1")
    fun legacyPreferences(): Flow<LegacyPreferences?>

    @Query("SELECT * FROM legacy_preferences WHERE id = 1")
    suspend fun currentLegacyPreferences(): LegacyPreferences?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLegacyPreferences(value: LegacyPreferences)

    @Query("DELETE FROM movements") suspend fun clearMovements()

    @Query("DELETE FROM bags") suspend fun clearBags()

    @Query("DELETE FROM sessions") suspend fun clearSessions()

    @Query("DELETE FROM legacy_records") suspend fun clearLegacyRecords()

    @Query("DELETE FROM legacy_metadata") suspend fun clearLegacyMetadata()

    @Query("DELETE FROM legacy_preferences") suspend fun clearLegacyPreferences()

    @Query("SELECT COALESCE(SUM(initial),0) FROM bags WHERE sourceSession = :sessionId")
    suspend fun allocated(sessionId: String): Long
}

@Database(
    entities =
        [
            Session::class,
            Bag::class,
            Movement::class,
            LegacyRecord::class,
            LegacyMetadata::class,
            LegacyPreferences::class,
            DeletionMarker::class,
        ],
    version = 3,
    exportSchema = true,
)
abstract class PumpDatabase : RoomDatabase() {
    abstract fun dao(): PumpDao
}
