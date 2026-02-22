package com.example.runningapp.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class RunnerSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long = 0,
    val durationSeconds: Long = 0,
    val avgBpm: Int = 0,
    val maxBpm: Int = 0,
    val timeInTargetZoneSeconds: Long = 0,
    val zone1Seconds: Long = 0,
    val zone2Seconds: Long = 0,
    val zone3Seconds: Long = 0,
    val zone4Seconds: Long = 0,
    val zone5Seconds: Long = 0,
    val runMode: String = "treadmill",
    val distanceKm: Double = 0.0,
    val avgPaceMinPerKm: Double = 0.0,
    val noDataSeconds: Long = 0L,
    val walkBreaksCount: Int = 0,
    val isRunWalkMode: Boolean = false
)

@Entity(
    tableName = "hr_samples",
    foreignKeys = [
        ForeignKey(
            entity = RunnerSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class HrSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val elapsedSeconds: Long,
    val rawBpm: Int,
    val smoothedBpm: Int,
    val connectionState: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val paceMinPerKm: Double? = null
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: RunnerSession): Long

    @Update
    suspend fun updateSession(session: RunnerSession)

    @Query("SELECT * FROM sessions ORDER BY startTime DESC LIMIT 20")
    fun getLast20Sessions(): Flow<List<RunnerSession>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): RunnerSession?

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    fun getSessionByIdFlow(sessionId: Long): Flow<RunnerSession?>

    @Query("SELECT * FROM sessions WHERE endTime > 0 AND durationSeconds > 120 ORDER BY startTime DESC LIMIT 3")
    suspend fun getLast3CompletedSessions(): List<RunnerSession>

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: Long)

    @Query("DELETE FROM sessions WHERE id IN (:sessionIds)")
    suspend fun deleteSessionsByIds(sessionIds: List<Long>)
}

@Dao
interface SampleDao {
    @Insert
    suspend fun insertSample(sample: HrSample)

    @Query("SELECT * FROM hr_samples WHERE sessionId = :sessionId ORDER BY elapsedSeconds ASC")
    fun getSamplesForSession(sessionId: Long): Flow<List<HrSample>>
}

@Database(entities = [RunnerSession::class, HrSample::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sampleDao(): SampleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "running_app_db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN zone1Seconds INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE sessions ADD COLUMN zone2Seconds INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE sessions ADD COLUMN zone3Seconds INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE sessions ADD COLUMN zone4Seconds INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE sessions ADD COLUMN zone5Seconds INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN runMode TEXT NOT NULL DEFAULT 'treadmill'")
        database.execSQL("ALTER TABLE sessions ADD COLUMN distanceKm REAL NOT NULL DEFAULT 0.0")
        database.execSQL("ALTER TABLE sessions ADD COLUMN avgPaceMinPerKm REAL NOT NULL DEFAULT 0.0")
        
        database.execSQL("ALTER TABLE hr_samples ADD COLUMN latitude REAL")
        database.execSQL("ALTER TABLE hr_samples ADD COLUMN longitude REAL")
        database.execSQL("ALTER TABLE hr_samples ADD COLUMN paceMinPerKm REAL")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN noDataSeconds INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN walkBreaksCount INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE sessions ADD COLUMN isRunWalkMode INTEGER NOT NULL DEFAULT 0")
    }
}
