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
    val includeInAiTraining: Boolean = true,
    val easyPlannedDurationSeconds: Int? = null,
    val easyActualDurationSeconds: Int? = null,
    val easyTotalJogSeconds: Int? = null,
    val easyTotalWalkSeconds: Int? = null,
    val easyJogPercent: Int? = null,
    val easyLongestJogBoutSeconds: Int? = null,
    val easyWalkInterruptions: Int? = null,
    val easyHrSummary: String? = null,
    val easyTimeAboveCapSeconds: Int? = null,
    val easyDataQualitySummary: String? = null,
    // Post-run "How did that feel?" feedback. Context only — must never feed
    // the Effort/TRIMP or Fitness/Fatigue/Form math (#60).
    val perceivedEffort: Int? = null,
    val sessionNote: String? = null,
    // Start position, captured from the first GPS fix of the run. Null for treadmill/no-GPS
    // sessions, or if no fix arrived before the run ended.
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
    // Weather snapshot at save (#79), fetched from Open-Meteo off the save path.
    val weatherTempC: Double? = null,
    val weatherFeelsLikeC: Double? = null,
    val weatherHumidityPercent: Int? = null,
    val weatherWindSpeedKmh: Double? = null,
    val weatherConditionCode: Int? = null
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
    val totalTimeSpentWalkingDuringRunIntervalSeconds: Int,
    val avgHrAtTriggerInInterval: Double? = null,
    val avgRecoverySecondsAfterTriggerInInterval: Double? = null
)

data class MaxSessionLoad30dProjection(
    val maxDistanceKm: Double?,
    val maxDurationSeconds: Long?
)

object TrackPointSource {
    const val GPS = "GPS"
    const val BACKFILL = "BACKFILL"
}

@Entity(
    tableName = "track_points",
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
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    // Null for BACKFILL points — historical hr_samples breadcrumbs never recorded GPS accuracy.
    val horizontalAccuracyMeters: Float? = null,
    val verticalAccuracyMeters: Float? = null,
    val speedMps: Float? = null,
    // Raw barometer pressure at the time of the fix, hPa. Null on phones without a barometer.
    val barometerPressureHpa: Float? = null,
    val timestampMillis: Long,
    val source: String
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

    @Query("UPDATE sessions SET perceivedEffort = :effort, sessionNote = :note WHERE id = :sessionId")
    suspend fun updateFeelFeedback(sessionId: Long, effort: Int?, note: String?)

    @Query(
        """
        UPDATE sessions
        SET weatherTempC = :tempC,
            weatherFeelsLikeC = :feelsLikeC,
            weatherHumidityPercent = :humidityPercent,
            weatherWindSpeedKmh = :windSpeedKmh,
            weatherConditionCode = :conditionCode
        WHERE id = :sessionId
        """
    )
    suspend fun updateWeather(
        sessionId: Long,
        tempC: Double,
        feelsLikeC: Double,
        humidityPercent: Int,
        windSpeedKmh: Double,
        conditionCode: Int
    )

    @Query(
        """
        SELECT * FROM sessions
        WHERE runMode = 'outdoor'
          AND startLatitude IS NOT NULL
          AND startLongitude IS NOT NULL
          AND weatherTempC IS NULL
          AND endTime > 0
        """
    )
    suspend fun getOutdoorSessionsMissingWeather(): List<RunnerSession>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    fun getSessionByIdFlow(sessionId: Long): Flow<RunnerSession?>

    @Query("SELECT * FROM sessions WHERE endTime > 0 AND durationSeconds > 120 ORDER BY startTime DESC LIMIT 3")
    suspend fun getLast3CompletedSessions(): List<RunnerSession>

    @Query("SELECT * FROM sessions WHERE endTime > 0 AND durationSeconds > 120 AND includeInAiTraining = 1 ORDER BY startTime DESC LIMIT 3")
    suspend fun getLast3AiEligibleCompletedSessions(): List<RunnerSession>

    @Query("SELECT * FROM sessions WHERE endTime > 0 ORDER BY endTime DESC LIMIT 1")
    suspend fun getMostRecentFinalizedSession(): RunnerSession?

    @Query(
        """
        SELECT
            MAX(CASE WHEN distanceKm > 0 THEN distanceKm END) AS maxDistanceKm,
            MAX(durationSeconds) AS maxDurationSeconds
        FROM sessions
        WHERE endTime > 0
          AND endTime >= :cutoffEpochMillis
        """
    )
    suspend fun getMaxSessionLoadLast30Days(cutoffEpochMillis: Long): MaxSessionLoad30dProjection

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

    @Query("SELECT * FROM hr_samples WHERE sessionId = :sessionId ORDER BY elapsedSeconds ASC")
    suspend fun getSamplesForSessionOnce(sessionId: Long): List<HrSample>
}

@Dao
interface TrackPointDao {
    @Insert
    suspend fun insertTrackPoint(trackPoint: TrackPoint)

    @Insert
    suspend fun insertTrackPoints(trackPoints: List<TrackPoint>)

    @Query("SELECT * FROM track_points WHERE sessionId = :sessionId ORDER BY timestampMillis ASC")
    fun getTrackPointsForSession(sessionId: Long): Flow<List<TrackPoint>>

    @Query("SELECT * FROM track_points WHERE sessionId = :sessionId ORDER BY timestampMillis ASC")
    suspend fun getTrackPointsForSessionOnce(sessionId: Long): List<TrackPoint>
}

@Dao
interface RunWalkIntervalStatDao {
    @Insert
    suspend fun insertIntervalStat(stat: RunWalkIntervalStat): Long

    @Insert
    suspend fun insertIntervalStats(stats: List<RunWalkIntervalStat>)

    @Query("SELECT * FROM run_walk_interval_stats WHERE sessionId = :sessionId ORDER BY intervalIndex ASC")
    fun getIntervalStatsForSessionFlow(sessionId: Long): Flow<List<RunWalkIntervalStat>>

    @Query("SELECT * FROM run_walk_interval_stats WHERE sessionId = :sessionId ORDER BY intervalIndex ASC")
    suspend fun getIntervalStatsForSession(sessionId: Long): List<RunWalkIntervalStat>
}

@Database(
    entities = [RunnerSession::class, HrSample::class, RunWalkIntervalStat::class, TrackPoint::class],
    version = 12,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sampleDao(): SampleDao
    abstract fun runWalkIntervalStatDao(): RunWalkIntervalStatDao
    abstract fun trackPointDao(): TrackPointDao

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
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12
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

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE run_walk_interval_stats ADD COLUMN avgHrAtTriggerInInterval REAL"
        )
        database.execSQL(
            "ALTER TABLE run_walk_interval_stats ADD COLUMN avgRecoverySecondsAfterTriggerInInterval REAL"
        )
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyPlannedDurationSeconds INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyActualDurationSeconds INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyTotalJogSeconds INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyTotalWalkSeconds INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyJogPercent INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyLongestJogBoutSeconds INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyWalkInterruptions INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyHrSummary TEXT")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyTimeAboveCapSeconds INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyDataQualitySummary TEXT")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN perceivedEffort INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN sessionNote TEXT")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN startLatitude REAL")
        database.execSQL("ALTER TABLE sessions ADD COLUMN startLongitude REAL")
        database.execSQL("ALTER TABLE sessions ADD COLUMN weatherTempC REAL")
        database.execSQL("ALTER TABLE sessions ADD COLUMN weatherFeelsLikeC REAL")
        database.execSQL("ALTER TABLE sessions ADD COLUMN weatherHumidityPercent INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN weatherWindSpeedKmh REAL")
        database.execSQL("ALTER TABLE sessions ADD COLUMN weatherConditionCode INTEGER")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `track_points` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `latitude` REAL NOT NULL,
                `longitude` REAL NOT NULL,
                `altitudeMeters` REAL,
                `horizontalAccuracyMeters` REAL,
                `verticalAccuracyMeters` REAL,
                `speedMps` REAL,
                `barometerPressureHpa` REAL,
                `timestampMillis` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_track_points_sessionId` ON `track_points` (`sessionId`)"
        )
        backfillTrackPointsFromHrSamples(database)
    }
}

/**
 * Backfills historical hr_samples lat/lon breadcrumbs into track_points as BACKFILL rows,
 * skipping consecutive duplicate coordinates per session. Walks a Cursor rather than using a
 * SQL window function for the dedup, since window functions need SQLite 3.25+ and this app's
 * minSdk 26 devices can ship with older bundled SQLite.
 */
private fun backfillTrackPointsFromHrSamples(database: SupportSQLiteDatabase) {
    val cursor = database.query(
        """
        SELECT h.sessionId, h.latitude, h.longitude, h.elapsedSeconds, s.startTime
        FROM hr_samples h
        JOIN sessions s ON s.id = h.sessionId
        WHERE h.latitude IS NOT NULL AND h.longitude IS NOT NULL
        ORDER BY h.sessionId ASC, h.elapsedSeconds ASC
        """.trimIndent()
    )
    val insertStatement = database.compileStatement(
        """
        INSERT INTO track_points
            (sessionId, latitude, longitude, altitudeMeters, horizontalAccuracyMeters, verticalAccuracyMeters, speedMps, barometerPressureHpa, timestampMillis, source)
        VALUES (?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?, 'BACKFILL')
        """.trimIndent()
    )
    cursor.use { c ->
        var lastSessionId: Long? = null
        var lastLatitude: Double? = null
        var lastLongitude: Double? = null
        while (c.moveToNext()) {
            val sessionId = c.getLong(0)
            val latitude = c.getDouble(1)
            val longitude = c.getDouble(2)
            val elapsedSeconds = c.getLong(3)
            val startTime = c.getLong(4)

            val isConsecutiveDuplicate = sessionId == lastSessionId &&
                latitude == lastLatitude &&
                longitude == lastLongitude
            if (!isConsecutiveDuplicate) {
                insertStatement.bindLong(1, sessionId)
                insertStatement.bindDouble(2, latitude)
                insertStatement.bindDouble(3, longitude)
                insertStatement.bindLong(4, startTime + elapsedSeconds * 1000)
                insertStatement.executeInsert()
                insertStatement.clearBindings()
            }
            lastSessionId = sessionId
            lastLatitude = latitude
            lastLongitude = longitude
        }
    }
}
