package com.example.runningapp.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.runningapp.HrZone
import com.example.runningapp.effectiveMaxHr
import com.example.runningapp.hrZoneOf
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class RunnerSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long = 0,
    val durationSeconds: Long = 0,
    val avgBpm: Int = 0,
    val maxBpm: Int = 0,
    // The target this run actually had, so in-target time survives a later change to the global
    // (#97). Written from the global today; the workout becomes its source in #107.
    val targetZone: Int = HrZone.DEFAULT_TARGET.number,
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
    val includeInAiTraining: Boolean = true,
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

/**
 * Whether the run has been saved with its totals. A row is inserted when the run starts and
 * stamped with an end time only when it finishes, so a zero here means the run is still being
 * written to — the state anything reading a run as a whole (the GPX export, #84) must wait for.
 */
fun RunnerSession.isFinished(): Boolean = endTime > 0

/** The five zone columns, reachable by zone rather than by name. */
fun RunnerSession.secondsInZone(zone: HrZone): Long = when (zone) {
    HrZone.ENDURANCE -> zone1Seconds
    HrZone.MODERATE -> zone2Seconds
    HrZone.TEMPO -> zone3Seconds
    HrZone.THRESHOLD -> zone4Seconds
    HrZone.ANAEROBIC -> zone5Seconds
}

/**
 * Time on target: exactly the target zone's own seconds.
 *
 * Derived rather than stored. The old `timeInTargetZoneSeconds` column banked by *band* while
 * the five zone columns banked by *zone*, so it could only ever disagree with the numbers beside
 * it (#106). Now that a target is a zone, "in target" means "in zone N" and there is nothing
 * left to store.
 */
val RunnerSession.inTargetZoneSeconds: Long
    get() = secondsInZone(HrZone.ofNumberOrDefault(targetZone))

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
    val paceMinPerKm: Double? = null,
    // Wall clock of the second this sample was banked. [elapsedSeconds] counts *running* seconds, so
    // it stops during a pause and no longer says when the reading happened — which is what anything
    // lining heart rate up against the GPS track needs (#84). Null on rows written before v16.
    val timestampMillis: Long? = null
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
    val source: String,
    // True on the first fix recorded after the run resumed — the far side of a pause, and the only
    // record that one happened at all. Nothing else marks it: a manual pause stops GPS and an
    // auto-pause is not written to the track, so a pause leaves only an absence, and a short one
    // leaves an absence too small to tell from a sparse patch of a run in progress. Anything drawing
    // or measuring the route must break here rather than joining across ground the runner covered
    // while stopped (#84). False on rows written before v17.
    val startsAfterPause: Boolean = false
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

    /**
     * Finished runs only — `endTime > 0` is what finalized means here, as in the queries above.
     *
     * A run in progress must stay out of the one-shot retally (#112): the recorder finalizes it
     * from its own in-memory zone counters, so a retallied row would be overwritten anyway, and
     * the flag would be spent on a run that ends up inconsistent with it.
     */
    @Query("SELECT id FROM sessions WHERE endTime > 0")
    suspend fun getFinalizedSessionIds(): List<Long>

    @Query(
        """
        UPDATE sessions
        SET zone1Seconds = :zone1,
            zone2Seconds = :zone2,
            zone3Seconds = :zone3,
            zone4Seconds = :zone4,
            zone5Seconds = :zone5
        WHERE id = :sessionId
        """
    )
    suspend fun updateZoneSeconds(
        sessionId: Long,
        zone1: Long,
        zone2: Long,
        zone3: Long,
        zone4: Long,
        zone5: Long
    )

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

    // Just the beats, for re-tallying zone seconds one run at a time (#112) — the full rows would
    // be an order of magnitude more memory for a number that only needs the BPM.
    @Query("SELECT rawBpm FROM hr_samples WHERE sessionId = :sessionId")
    suspend fun getRawBpmsForSession(sessionId: Long): List<Int>
}

@Dao
interface TrackPointDao {
    @Insert
    suspend fun insertTrackPoint(trackPoint: TrackPoint)

    @Insert
    suspend fun insertTrackPoints(trackPoints: List<TrackPoint>)

    // Raw, unfiltered by accuracy — map-drawing callers should go through
    // SessionRepository.getTrackPointsForMap() instead so the #38 accuracy gate applies.
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
    version = 17,
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

        /**
         * [maxHrProvider] feeds the v12 → v13 zone recompute, which needs a Max HR that lives in
         * DataStore rather than in the database. It is read lazily, from inside the migration, so
         * the settings read happens on Room's own thread and only on the one launch that migrates.
         */
        fun getDatabase(context: android.content.Context, maxHrProvider: () -> Int): AppDatabase {
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
                    MIGRATION_11_12,
                    migration12To13(maxHrProvider),
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17
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

/**
 * Drops `timeInTargetZoneSeconds`, adds `targetZone`, and recomputes every run's zone times under
 * the Max-HR-derived zone model (#93).
 *
 * Zone times were frozen at save under the old hybrid model, so **every** run in the database
 * carries wrong numbers — most visibly Zone 3 time mis-filed as Zone 4, which the old model's
 * invertible Zone 3 window produced whenever `zone2High >= 0.8 × maxHr`. The recompute is silent
 * by design (#97): it runs against a Max HR already in effect, so there is nothing to decide.
 *
 * This is a one-shot correction, not a freeze. #112 recomputes again on the first *deliberate*
 * Max HR set — the point at which the number stops being a placeholder — and every change after
 * that is future-only.
 *
 * [maxHrProvider] is called here rather than closed over at construction so the DataStore read
 * happens on Room's own thread, and only on the launch that actually migrates.
 */
fun migration12To13(maxHrProvider: () -> Int) = object : Migration(12, 13) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // SQLite gained ALTER TABLE ... DROP COLUMN in 3.35, but minSdk 26 devices ship far older,
        // so dropping a column means rebuilding the table. Safe despite hr_samples, track_points
        // and run_walk_interval_stats all carrying FKs to sessions: Room runs migrations before it
        // turns foreign_keys on, and with the pragma off SQLite leaves other tables' REFERENCES
        // clauses untouched across the DROP/RENAME. Same shape Room's own auto-migrations emit.
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sessions_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `startTime` INTEGER NOT NULL,
                `endTime` INTEGER NOT NULL,
                `durationSeconds` INTEGER NOT NULL,
                `avgBpm` INTEGER NOT NULL,
                `maxBpm` INTEGER NOT NULL,
                `targetZone` INTEGER NOT NULL,
                `zone1Seconds` INTEGER NOT NULL,
                `zone2Seconds` INTEGER NOT NULL,
                `zone3Seconds` INTEGER NOT NULL,
                `zone4Seconds` INTEGER NOT NULL,
                `zone5Seconds` INTEGER NOT NULL,
                `runMode` TEXT NOT NULL,
                `distanceKm` REAL NOT NULL,
                `avgPaceMinPerKm` REAL NOT NULL,
                `noDataSeconds` INTEGER NOT NULL,
                `walkBreaksCount` INTEGER NOT NULL,
                `isRunWalkMode` INTEGER NOT NULL,
                `sessionType` TEXT NOT NULL,
                `includeInAiTraining` INTEGER NOT NULL,
                `easyPlannedDurationSeconds` INTEGER,
                `easyActualDurationSeconds` INTEGER,
                `easyTotalJogSeconds` INTEGER,
                `easyTotalWalkSeconds` INTEGER,
                `easyJogPercent` INTEGER,
                `easyLongestJogBoutSeconds` INTEGER,
                `easyWalkInterruptions` INTEGER,
                `easyHrSummary` TEXT,
                `easyTimeAboveCapSeconds` INTEGER,
                `easyDataQualitySummary` TEXT,
                `perceivedEffort` INTEGER,
                `sessionNote` TEXT,
                `startLatitude` REAL,
                `startLongitude` REAL,
                `weatherTempC` REAL,
                `weatherFeelsLikeC` REAL,
                `weatherHumidityPercent` INTEGER,
                `weatherWindSpeedKmh` REAL,
                `weatherConditionCode` INTEGER
            )
            """.trimIndent()
        )
        // Every past run is declared targetZone = 2: their target was a BPM band, not a zone, so
        // the real value is unreconstructible — but the old default band (120-140) was aiming at
        // easy/Z2 anyway (#97). A literal, not HrZone.DEFAULT_TARGET: this is a statement about
        // history, which must not move if the default ever does.
        database.execSQL(
            """
            INSERT INTO `sessions_new` (
                id, startTime, endTime, durationSeconds, avgBpm, maxBpm, targetZone,
                zone1Seconds, zone2Seconds, zone3Seconds, zone4Seconds, zone5Seconds,
                runMode, distanceKm, avgPaceMinPerKm, noDataSeconds, walkBreaksCount,
                isRunWalkMode, sessionType, includeInAiTraining,
                easyPlannedDurationSeconds, easyActualDurationSeconds, easyTotalJogSeconds,
                easyTotalWalkSeconds, easyJogPercent, easyLongestJogBoutSeconds,
                easyWalkInterruptions, easyHrSummary, easyTimeAboveCapSeconds,
                easyDataQualitySummary, perceivedEffort, sessionNote, startLatitude,
                startLongitude, weatherTempC, weatherFeelsLikeC, weatherHumidityPercent,
                weatherWindSpeedKmh, weatherConditionCode
            )
            SELECT
                id, startTime, endTime, durationSeconds, avgBpm, maxBpm, 2,
                zone1Seconds, zone2Seconds, zone3Seconds, zone4Seconds, zone5Seconds,
                runMode, distanceKm, avgPaceMinPerKm, noDataSeconds, walkBreaksCount,
                isRunWalkMode, sessionType, includeInAiTraining,
                easyPlannedDurationSeconds, easyActualDurationSeconds, easyTotalJogSeconds,
                easyTotalWalkSeconds, easyJogPercent, easyLongestJogBoutSeconds,
                easyWalkInterruptions, easyHrSummary, easyTimeAboveCapSeconds,
                easyDataQualitySummary, perceivedEffort, sessionNote, startLatitude,
                startLongitude, weatherTempC, weatherFeelsLikeC, weatherHumidityPercent,
                weatherWindSpeedKmh, weatherConditionCode
            FROM `sessions`
            """.trimIndent()
        )
        database.execSQL("DROP TABLE `sessions`")
        database.execSQL("ALTER TABLE `sessions_new` RENAME TO `sessions`")

        recomputeZoneSecondsFromHrSamples(database, maxHrProvider())
    }
}

/**
 * Re-tallies each session's zone seconds from its stored `hr_samples` rows.
 *
 * The tally is exact rather than an estimate: the recorder writes exactly one sample per second
 * of the run, and only when BPM > 0 — the same condition under which it banked a second of zone
 * time. So counting samples per zone reproduces what the run would have recorded had the new
 * model been in force. Seconds with no HR signal have no row and gain no zone time, leaving the
 * existing `noDataSeconds` figure meaningful and unfabricated.
 *
 * Walks a Cursor and derives in Kotlin rather than binning in SQL, following
 * [backfillTrackPointsFromHrSamples]: this keeps [hrZoneOf] the app's one classifier, and the
 * arithmetic off SQLite versions that predate window functions.
 */
private fun recomputeZoneSecondsFromHrSamples(database: SupportSQLiteDatabase, maxHr: Int) {
    val clampedMaxHr = effectiveMaxHr(maxHr)
    // Zero first so the tally below is a replacement rather than a correction: a run whose samples
    // are all gone must end up with no zone time, not with its old numbers left standing.
    database.execSQL(
        "UPDATE sessions SET zone1Seconds = 0, zone2Seconds = 0, zone3Seconds = 0, zone4Seconds = 0, zone5Seconds = 0"
    )
    val cursor = database.query(
        """
        SELECT sessionId, rawBpm
        FROM hr_samples
        ORDER BY sessionId ASC
        """.trimIndent()
    )
    val updateStatement = database.compileStatement(
        """
        UPDATE sessions
        SET zone1Seconds = ?, zone2Seconds = ?, zone3Seconds = ?, zone4Seconds = ?, zone5Seconds = ?
        WHERE id = ?
        """.trimIndent()
    )

    fun flush(sessionId: Long, zoneSeconds: LongArray) {
        zoneSeconds.forEachIndexed { index, seconds ->
            updateStatement.bindLong(index + 1, seconds)
        }
        updateStatement.bindLong(6, sessionId)
        updateStatement.executeUpdateDelete()
        updateStatement.clearBindings()
    }

    cursor.use { c ->
        var currentSessionId: Long? = null
        val zoneSeconds = LongArray(5)
        while (c.moveToNext()) {
            val sessionId = c.getLong(0)
            if (sessionId != currentSessionId) {
                currentSessionId?.let { flush(it, zoneSeconds) }
                currentSessionId = sessionId
                zoneSeconds.fill(0L)
            }
            val zone = hrZoneOf(c.getInt(1), clampedMaxHr)
            if (zone != null) {
                val index = zone.number - 1
                zoneSeconds[index] = zoneSeconds[index] + 1L
            }
        }
        currentSessionId?.let { flush(it, zoneSeconds) }
    }
}

/**
 * Rebuilds [RunnerSession.isRunWalkMode] from hard interval evidence for runs recorded before it
 * became the run's own run/walk flag (#107).
 *
 * Before #107 `isRunWalkMode` was written straight from the separate `runWalkCoachEnabled` setting
 * (`finalIsRunWalkMode = currentSettings.runWalkCoachEnabled`), independent of what the run actually
 * was. The new code treats the flag as the structured-workout truth for AI context and metrics, so
 * on upgraded databases it is wrong in both directions:
 *  - False positive: a run recorded with the coach toggle on but which never ran intervals has
 *    `isRunWalkMode = 1`. Because `evaluateAndAdjustPlan` now gates on this flag, such a row as the
 *    latest finalized session would send a non-plan run to Gemini and adjust or graduate the plan.
 *  - False negative: a real run/walk run recorded with the toggle off has `isRunWalkMode = 0`.
 *
 * The one durable, trustworthy signal is `run_walk_interval_stats`: those rows are written only when
 * a run actually executed run/walk intervals. So the flag is set from their presence and cleared
 * everywhere else. `sessionType` is deliberately NOT used — its column default backfilled every
 * pre-column row to "Run/Walk", so keying off that label would promote genuine open runs. A run/walk
 * run with no interval rows has no interval evidence to preserve anyway (`buildRunWalkMetrics`
 * returns null), so clearing it is safe. This runs once at the 13→14 upgrade when every row predates
 * #107, and new runs set the flag correctly at insert. Dropping the dead `sessionType`/`easy*`
 * columns lands in [MIGRATION_14_15]; the fuller analytics reconciliation stays with the analytics
 * cluster.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            UPDATE sessions
            SET isRunWalkMode = CASE
                WHEN id IN (SELECT DISTINCT sessionId FROM run_walk_interval_stats) THEN 1
                ELSE 0
            END
            """.trimIndent()
        )
    }
}

/**
 * Drops `sessionType` and the ten `easy*` columns — the last physical remains of the four session
 * types (#107), carried here by #113.
 *
 * Both were dead before this: `sessionType` was replaced as the run/walk truth by `isRunWalkMode`
 * in [MIGRATION_13_14], which also records *why* the label could never be trusted, and the `easy*`
 * columns belonged to Easy Fixed Duration, a mode that no longer exists. Nothing reads either.
 * Leaving them would keep a column that says "Run/Walk" beside runs the app now knows were open
 * runs — a stored contradiction waiting to be believed by whatever reads the table next.
 *
 * A table rebuild for the same reason [migration12To13] needed one: minSdk 26 devices ship SQLite
 * older than 3.35, so `ALTER TABLE ... DROP COLUMN` is not available. Same shape, same safety
 * argument about the three tables holding foreign keys into `sessions` — Room runs migrations with
 * `foreign_keys` off, so their REFERENCES clauses survive the DROP/RENAME untouched. Every
 * surviving column is copied value-for-value: this migration only removes, it computes nothing.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sessions_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `startTime` INTEGER NOT NULL,
                `endTime` INTEGER NOT NULL,
                `durationSeconds` INTEGER NOT NULL,
                `avgBpm` INTEGER NOT NULL,
                `maxBpm` INTEGER NOT NULL,
                `targetZone` INTEGER NOT NULL,
                `zone1Seconds` INTEGER NOT NULL,
                `zone2Seconds` INTEGER NOT NULL,
                `zone3Seconds` INTEGER NOT NULL,
                `zone4Seconds` INTEGER NOT NULL,
                `zone5Seconds` INTEGER NOT NULL,
                `runMode` TEXT NOT NULL,
                `distanceKm` REAL NOT NULL,
                `avgPaceMinPerKm` REAL NOT NULL,
                `noDataSeconds` INTEGER NOT NULL,
                `walkBreaksCount` INTEGER NOT NULL,
                `isRunWalkMode` INTEGER NOT NULL,
                `includeInAiTraining` INTEGER NOT NULL,
                `perceivedEffort` INTEGER,
                `sessionNote` TEXT,
                `startLatitude` REAL,
                `startLongitude` REAL,
                `weatherTempC` REAL,
                `weatherFeelsLikeC` REAL,
                `weatherHumidityPercent` INTEGER,
                `weatherWindSpeedKmh` REAL,
                `weatherConditionCode` INTEGER
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO `sessions_new` (
                id, startTime, endTime, durationSeconds, avgBpm, maxBpm, targetZone,
                zone1Seconds, zone2Seconds, zone3Seconds, zone4Seconds, zone5Seconds,
                runMode, distanceKm, avgPaceMinPerKm, noDataSeconds, walkBreaksCount,
                isRunWalkMode, includeInAiTraining, perceivedEffort, sessionNote,
                startLatitude, startLongitude, weatherTempC, weatherFeelsLikeC,
                weatherHumidityPercent, weatherWindSpeedKmh, weatherConditionCode
            )
            SELECT
                id, startTime, endTime, durationSeconds, avgBpm, maxBpm, targetZone,
                zone1Seconds, zone2Seconds, zone3Seconds, zone4Seconds, zone5Seconds,
                runMode, distanceKm, avgPaceMinPerKm, noDataSeconds, walkBreaksCount,
                isRunWalkMode, includeInAiTraining, perceivedEffort, sessionNote,
                startLatitude, startLongitude, weatherTempC, weatherFeelsLikeC,
                weatherHumidityPercent, weatherWindSpeedKmh, weatherConditionCode
            FROM `sessions`
            """.trimIndent()
        )
        database.execSQL("DROP TABLE `sessions`")
        database.execSQL("ALTER TABLE `sessions_new` RENAME TO `sessions`")
    }
}

/**
 * Records when each heart-rate sample was actually taken (#84).
 *
 * `elapsedSeconds` counts the Run's *running* seconds, so it stands still through a pause and can no
 * longer say what time a reading belongs to. Track points are stamped with the wall clock, so
 * without this column every point after the first pause — including every auto-pause at a crossing —
 * lines up against the wrong reading, or none at all.
 *
 * Nullable and not backfilled: for a run that was never paused, `startTime + elapsedSeconds` is the
 * same answer, and readers derive it that way when the column is null. Inventing a stored timestamp
 * for older paused runs would only record a guess as a fact.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE hr_samples ADD COLUMN timestampMillis INTEGER")
    }
}

/**
 * Records where a pause fell, on the fix that resumed the run (#84).
 *
 * Not nullable and not backfilled: false is the honest answer for every existing row. Where a pause
 * fell on a run recorded before v17 was never written down and cannot be recovered — those runs keep
 * the old behaviour, where only a gap longer than twenty seconds breaks the route.
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE track_points ADD COLUMN startsAfterPause INTEGER NOT NULL DEFAULT 0")
    }
}
