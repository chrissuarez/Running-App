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
    val isRunWalkMode: Boolean = false,
    val sessionType: String = "Run/Walk",
    val includeInAiTraining: Boolean = true
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

@Entity(
    tableName = "run_walk_interval_stats",
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
data class RunWalkIntervalStat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val intervalIndex: Int,
    val plannedDurationSeconds: Int,
    val actualRunningDurationBeforeHrTriggerSeconds: Int,
    val timeIntoIntervalWhenHrExceededCapSeconds: Int? = null,
    val hrTriggerEvents: Int,
    val totalTimeSpentWalkingDuringRunIntervalSeconds: Int
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

    @Query("SELECT * FROM sessions WHERE endTime > 0 AND durationSeconds > 120 AND includeInAiTraining = 1 ORDER BY startTime DESC LIMIT 3")
    suspend fun getLast3AiEligibleCompletedSessions(): List<RunnerSession>

    @Query("SELECT * FROM sessions WHERE endTime > 0 ORDER BY endTime DESC LIMIT 1")
    suspend fun getMostRecentFinalizedSession(): RunnerSession?

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

@Dao
interface RunWalkIntervalStatDao {
    @Insert
    suspend fun insertIntervalStat(stat: RunWalkIntervalStat): Long

    @Insert
    suspend fun insertIntervalStats(stats: List<RunWalkIntervalStat>)

    @Query("SELECT * FROM run_walk_interval_stats WHERE sessionId = :sessionId ORDER BY intervalIndex ASC")
    suspend fun getIntervalStatsForSession(sessionId: Long): List<RunWalkIntervalStat>
}

@Database(
    entities = [RunnerSession::class, HrSample::class, RunWalkIntervalStat::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sampleDao(): SampleDao
    abstract fun runWalkIntervalStatDao(): RunWalkIntervalStatDao

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
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7
                )
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

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN sessionType TEXT NOT NULL DEFAULT 'Run/Walk'")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN includeInAiTraining INTEGER NOT NULL DEFAULT 1")
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `run_walk_interval_stats` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `intervalIndex` INTEGER NOT NULL,
                `plannedDurationSeconds` INTEGER NOT NULL,
                `actualRunningDurationBeforeHrTriggerSeconds` INTEGER NOT NULL,
                `timeIntoIntervalWhenHrExceededCapSeconds` INTEGER,
                `hrTriggerEvents` INTEGER NOT NULL,
                `totalTimeSpentWalkingDuringRunIntervalSeconds` INTEGER NOT NULL,
                FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_run_walk_interval_stats_sessionId` ON `run_walk_interval_stats` (`sessionId`)"
        )
    }
}
