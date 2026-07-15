package com.example.runningapp.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers MIGRATION_11_12: creating track_points and backfilling historical hr_samples
 * lat/lon breadcrumbs into it. The repo doesn't export Room schema JSON (exportSchema =
 * false, no app/schemas history), so MigrationTestHelper's schema-file-driven
 * createDatabase() isn't available here. Instead this hand-builds a v11 database matching
 * the current entity definitions (sessions/hr_samples/run_walk_interval_stats are untouched
 * by this migration, so their v11 shape is just today's entity shape), runs the real
 * migration against it, then opens the result through Room to read back track_points.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val dbName = "migration-test-11-12.db"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate11To12_backfillsTrackPointsFromHrSamples_dedupingConsecutivePositionsPerSession() {
        val dbPath = context.getDatabasePath(dbName)
        val rawDb = SQLiteDatabase.openOrCreateDatabase(dbPath, null)
        rawDb.execSQL(
            """
            CREATE TABLE `sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `startTime` INTEGER NOT NULL,
                `endTime` INTEGER NOT NULL,
                `durationSeconds` INTEGER NOT NULL,
                `avgBpm` INTEGER NOT NULL,
                `maxBpm` INTEGER NOT NULL,
                `timeInTargetZoneSeconds` INTEGER NOT NULL,
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
        rawDb.execSQL(
            """
            CREATE TABLE `hr_samples` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `elapsedSeconds` INTEGER NOT NULL,
                `rawBpm` INTEGER NOT NULL,
                `smoothedBpm` INTEGER NOT NULL,
                `connectionState` TEXT NOT NULL,
                `latitude` REAL,
                `longitude` REAL,
                `paceMinPerKm` REAL,
                FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        rawDb.execSQL("CREATE INDEX `index_hr_samples_sessionId` ON `hr_samples` (`sessionId`)")
        rawDb.execSQL(
            """
            CREATE TABLE `run_walk_interval_stats` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `intervalIndex` INTEGER NOT NULL,
                `plannedDurationSeconds` INTEGER NOT NULL,
                `actualRunningDurationBeforeHrTriggerSeconds` INTEGER NOT NULL,
                `timeIntoIntervalWhenHrExceededCapSeconds` INTEGER,
                `hrTriggerEvents` INTEGER NOT NULL,
                `totalTimeSpentWalkingDuringRunIntervalSeconds` INTEGER NOT NULL,
                `avgHrAtTriggerInInterval` REAL,
                `avgRecoverySecondsAfterTriggerInInterval` REAL,
                FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        rawDb.execSQL("CREATE INDEX `index_run_walk_interval_stats_sessionId` ON `run_walk_interval_stats` (`sessionId`)")

        val sessionAStart = 1_000_000L
        val sessionBStart = 2_000_000L
        rawDb.execSQL(
            "INSERT INTO sessions (id, startTime, endTime, durationSeconds, avgBpm, maxBpm, timeInTargetZoneSeconds, " +
                "zone1Seconds, zone2Seconds, zone3Seconds, zone4Seconds, zone5Seconds, runMode, distanceKm, " +
                "avgPaceMinPerKm, noDataSeconds, walkBreaksCount, isRunWalkMode, sessionType, includeInAiTraining) " +
                "VALUES (1, $sessionAStart, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 'outdoor', 0.0, 0.0, 0, 0, 0, 'Run/Walk', 1)"
        )
        rawDb.execSQL(
            "INSERT INTO sessions (id, startTime, endTime, durationSeconds, avgBpm, maxBpm, timeInTargetZoneSeconds, " +
                "zone1Seconds, zone2Seconds, zone3Seconds, zone4Seconds, zone5Seconds, runMode, distanceKm, " +
                "avgPaceMinPerKm, noDataSeconds, walkBreaksCount, isRunWalkMode, sessionType, includeInAiTraining) " +
                "VALUES (2, $sessionBStart, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 'outdoor', 0.0, 0.0, 0, 0, 0, 'Run/Walk', 1)"
        )

        // Session A: identical fix repeated (dedup), a no-GPS gap, then a new position.
        rawDb.execSQL("INSERT INTO hr_samples (sessionId, elapsedSeconds, rawBpm, smoothedBpm, connectionState, latitude, longitude) VALUES (1, 1, 120, 120, 'Connected', 40.0, -70.0)")
        rawDb.execSQL("INSERT INTO hr_samples (sessionId, elapsedSeconds, rawBpm, smoothedBpm, connectionState, latitude, longitude) VALUES (1, 2, 121, 121, 'Connected', 40.0, -70.0)")
        rawDb.execSQL("INSERT INTO hr_samples (sessionId, elapsedSeconds, rawBpm, smoothedBpm, connectionState, latitude, longitude) VALUES (1, 3, 122, 122, 'Connected', 40.0, -70.0)")
        rawDb.execSQL("INSERT INTO hr_samples (sessionId, elapsedSeconds, rawBpm, smoothedBpm, connectionState, latitude, longitude) VALUES (1, 4, 123, 123, 'Connected', NULL, NULL)")
        rawDb.execSQL("INSERT INTO hr_samples (sessionId, elapsedSeconds, rawBpm, smoothedBpm, connectionState, latitude, longitude) VALUES (1, 5, 124, 124, 'Connected', 40.1, -70.1)")

        // Session B: same coordinates as session A's last point — must not be treated as a
        // cross-session duplicate.
        rawDb.execSQL("INSERT INTO hr_samples (sessionId, elapsedSeconds, rawBpm, smoothedBpm, connectionState, latitude, longitude) VALUES (2, 1, 130, 130, 'Connected', 40.1, -70.1)")

        rawDb.version = 11
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_11_12)
            .build()

        val sessionATrackPoints = runBlockingGet { migratedDb.trackPointDao().getTrackPointsForSessionOnce(1) }
        val sessionBTrackPoints = runBlockingGet { migratedDb.trackPointDao().getTrackPointsForSessionOnce(2) }
        migratedDb.close()

        assertEquals(2, sessionATrackPoints.size)
        assertEquals(40.0, sessionATrackPoints[0].latitude, 0.0)
        assertEquals(-70.0, sessionATrackPoints[0].longitude, 0.0)
        assertEquals(sessionAStart + 1 * 1000, sessionATrackPoints[0].timestampMillis)
        assertEquals(40.1, sessionATrackPoints[1].latitude, 0.0)
        assertEquals(-70.1, sessionATrackPoints[1].longitude, 0.0)
        assertEquals(sessionAStart + 5 * 1000, sessionATrackPoints[1].timestampMillis)
        sessionATrackPoints.forEach { point ->
            assertEquals(TrackPointSource.BACKFILL, point.source)
            assertNull(point.altitudeMeters)
            assertNull(point.horizontalAccuracyMeters)
            assertNull(point.verticalAccuracyMeters)
            assertNull(point.speedMps)
            assertNull(point.barometerPressureHpa)
        }

        assertEquals(1, sessionBTrackPoints.size)
        assertEquals(40.1, sessionBTrackPoints[0].latitude, 0.0)
        assertEquals(-70.1, sessionBTrackPoints[0].longitude, 0.0)
        assertEquals(TrackPointSource.BACKFILL, sessionBTrackPoints[0].source)
    }

    private fun <T> runBlockingGet(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}
