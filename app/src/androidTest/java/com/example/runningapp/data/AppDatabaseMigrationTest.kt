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
 * Covers the migrations that carry real data forward rather than just adding columns:
 * MIGRATION_11_12 (backfilling track_points from hr_samples breadcrumbs) and
 * [migration12To13] (recomputing every run's zone times under the Max-HR-derived model).
 *
 * The repo doesn't export Room schema JSON (exportSchema = false, no app/schemas history), so
 * MigrationTestHelper's schema-file-driven createDatabase() isn't available here. Instead these
 * hand-build the old database and run the real migrations against it, then open the result
 * through Room — which also proves the post-migration schema matches today's entities, since
 * Room refuses to open a database whose shape it does not recognise.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val dbName = "migration-test.db"
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
        val rawDb = openLegacyDatabase()

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

        // 12 -> 13 comes along for the ride: Room opens at the current version, so it runs every
        // migration between the file's version and today's. It does not disturb what this test
        // asserts — it touches sessions, never track_points.
        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_11_12, migration12To13 { 190 }, MIGRATION_13_14, MIGRATION_14_15)
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

    @Test
    fun migrate12To13_recomputesZoneSecondsFromHrSamples_andDeclaresHistoryTargetZone2() {
        val rawDb = openLegacyDatabase()
        createTrackPointsTable(rawDb)

        // Session 1: the headline defect — five seconds at 145 bpm, banked as Zone 4 by the old
        // hybrid model. At Max HR 190 the new model puts 133-151 squarely in Zone 3.
        insertLegacySession(rawDb, id = 1, zone3Seconds = 0, zone4Seconds = 5, noDataSeconds = 0)
        repeat(5) { insertSample(rawDb, sessionId = 1, elapsedSeconds = it + 1, rawBpm = 145) }

        // Session 2: one second in each zone, plus a stored no-data figure the recompute must
        // leave alone. 100 -> Z1, 120 -> Z2, 140 -> Z3, 160 -> Z4, 180 -> Z5.
        insertLegacySession(rawDb, id = 2, zone1Seconds = 999, noDataSeconds = 7)
        listOf(100, 120, 140, 160, 180).forEachIndexed { index, bpm ->
            insertSample(rawDb, sessionId = 2, elapsedSeconds = index + 1, rawBpm = bpm)
        }

        // Session 3: a run with no samples at all. Its stale zone times must go, not survive.
        insertLegacySession(rawDb, id = 3, zone2Seconds = 999, noDataSeconds = 42)

        rawDb.version = 12
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(migration12To13 { 190 }, MIGRATION_13_14, MIGRATION_14_15)
            .build()
        val session1 = runBlockingGet { migratedDb.sessionDao().getSessionById(1) }!!
        val session2 = runBlockingGet { migratedDb.sessionDao().getSessionById(2) }!!
        val session3 = runBlockingGet { migratedDb.sessionDao().getSessionById(3) }!!
        migratedDb.close()

        // Zone 3 time is recovered from Zone 4.
        assertEquals(5L, session1.zone3Seconds)
        assertEquals(0L, session1.zone4Seconds)

        assertEquals(1L, session2.zone1Seconds)
        assertEquals(1L, session2.zone2Seconds)
        assertEquals(1L, session2.zone3Seconds)
        assertEquals(1L, session2.zone4Seconds)
        assertEquals(1L, session2.zone5Seconds)
        // The gap keeps its own figure and buys no zone time.
        assertEquals(7L, session2.noDataSeconds)

        assertEquals(0L, session3.zone1Seconds)
        assertEquals(0L, session3.zone2Seconds)
        assertEquals(42L, session3.noDataSeconds)

        // Every pre-existing run is declared Zone 2 — its real target was a BPM band, so there is
        // nothing to reconstruct — and in-target now derives from that.
        listOf(session1, session2, session3).forEach { assertEquals(2, it.targetZone) }
        assertEquals(1L, session2.inTargetZoneSeconds)
    }

    @Test
    fun migrate13To14_rebuildsIsRunWalkModeFromIntervalEvidence_clearingLegacyToggleNoise() {
        val rawDb = openLegacyDatabase()
        // A real v12 database already has track_points (created by MIGRATION_11_12). The v12 -> v13
        // migration comes along for the ride when Room opens, so the table must be present or Room
        // rejects the post-migration schema.
        createTrackPointsTable(rawDb)

        // Session 1 — false negative: a real run/walk run whose flag was never set (the pre-#107
        // flag came from a separate coach toggle). Its interval stats are the durable evidence, so
        // it is promoted and the coach keeps that evidence.
        insertLegacySession(rawDb, id = 1, sessionType = "Run/Walk", isRunWalkMode = 0)
        rawDb.execSQL(
            "INSERT INTO run_walk_interval_stats (sessionId, intervalIndex, plannedDurationSeconds, " +
                "actualRunningDurationBeforeHrTriggerSeconds, hrTriggerEvents, " +
                "totalTimeSpentWalkingDuringRunIntervalSeconds) VALUES (1, 0, 180, 180, 0, 0)"
        )

        // Session 2 — false positive: recorded with the coach toggle on but never ran intervals, so
        // the old flag is set with no evidence behind it. Must be cleared, or evaluateAndAdjustPlan
        // would adjust the plan off a non-plan run.
        insertLegacySession(rawDb, id = 2, sessionType = "Zone 2 Walk", isRunWalkMode = 1)

        // Session 3 — the sessionType trap: the column default backfilled old open runs to
        // "Run/Walk". With no interval stats this is not a structured workout and must stay 0, so the
        // migration must not key off the label.
        insertLegacySession(rawDb, id = 3, sessionType = "Run/Walk", isRunWalkMode = 0)

        // Session 4: an open/continuous run (Free Track) left flagged by the toggle. No stats -> 0.
        insertLegacySession(rawDb, id = 4, sessionType = "Free Track", isRunWalkMode = 1)

        rawDb.version = 12
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(migration12To13 { 190 }, MIGRATION_13_14, MIGRATION_14_15)
            .build()
        val session1 = runBlockingGet { migratedDb.sessionDao().getSessionById(1) }!!
        val session2 = runBlockingGet { migratedDb.sessionDao().getSessionById(2) }!!
        val session3 = runBlockingGet { migratedDb.sessionDao().getSessionById(3) }!!
        val session4 = runBlockingGet { migratedDb.sessionDao().getSessionById(4) }!!
        migratedDb.close()

        assertEquals(true, session1.isRunWalkMode)
        assertEquals(false, session2.isRunWalkMode)
        assertEquals(false, session3.isRunWalkMode)
        assertEquals(false, session4.isRunWalkMode)
    }

    @Test
    fun migrate14To15_dropsTheDeadSessionTypeAndEasyColumns_carryingEveryRealValueForward() {
        val rawDb = openLegacyDatabase()
        createTrackPointsTable(rawDb)

        // One run with something in every kind of surviving column — required, optional, and the
        // late-added weather block — since a rebuild that mis-orders the SELECT would still produce
        // a schema Room accepts while silently shuffling values between columns.
        rawDb.execSQL(
            "INSERT INTO sessions (id, startTime, endTime, durationSeconds, avgBpm, maxBpm, " +
                "timeInTargetZoneSeconds, zone1Seconds, zone2Seconds, zone3Seconds, zone4Seconds, " +
                "zone5Seconds, runMode, distanceKm, avgPaceMinPerKm, noDataSeconds, walkBreaksCount, " +
                "isRunWalkMode, sessionType, includeInAiTraining, easyPlannedDurationSeconds, " +
                "easyHrSummary, perceivedEffort, sessionNote, startLatitude, startLongitude, " +
                "weatherTempC, weatherHumidityPercent, weatherConditionCode) " +
                "VALUES (1, 5000, 9000, 4000, 128, 171, 0, 11, 22, 33, 44, 55, 'outdoor', 7.25, 5.5, " +
                "12, 3, 1, 'Zone 2 Walk', 0, 1800, 'summary', 4, 'felt strong', 51.5, -0.12, " +
                "14.5, 71, 3)"
        )
        // The interval rows both keep session 1 flagged through 13 -> 14 and prove the foreign key
        // survives the table rebuild — they cascade-delete, so a broken FK shows up as a lost row.
        rawDb.execSQL(
            "INSERT INTO run_walk_interval_stats (sessionId, intervalIndex, plannedDurationSeconds, " +
                "actualRunningDurationBeforeHrTriggerSeconds, hrTriggerEvents, " +
                "totalTimeSpentWalkingDuringRunIntervalSeconds) VALUES (1, 0, 180, 180, 0, 0)"
        )

        rawDb.version = 12
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(migration12To13 { 190 }, MIGRATION_13_14, MIGRATION_14_15)
            .build()
        // Opening through Room is itself the assertion that the dead columns are gone: Room refuses
        // a database whose column set does not match the entity, and RunnerSession no longer
        // declares sessionType or any easy* field.
        val session = runBlockingGet { migratedDb.sessionDao().getSessionById(1) }!!
        val intervals = runBlockingGet { migratedDb.runWalkIntervalStatDao().getIntervalStatsForSession(1) }
        migratedDb.close()

        assertEquals(5000L, session.startTime)
        assertEquals(9000L, session.endTime)
        assertEquals(4000L, session.durationSeconds)
        assertEquals(128, session.avgBpm)
        assertEquals(171, session.maxBpm)
        assertEquals("outdoor", session.runMode)
        assertEquals(7.25, session.distanceKm, 0.0001)
        assertEquals(5.5, session.avgPaceMinPerKm, 0.0001)
        assertEquals(12L, session.noDataSeconds)
        assertEquals(3, session.walkBreaksCount)
        assertEquals(true, session.isRunWalkMode)
        assertEquals(false, session.includeInAiTraining)
        assertEquals(4, session.perceivedEffort)
        assertEquals("felt strong", session.sessionNote)
        assertEquals(51.5, session.startLatitude!!, 0.0001)
        assertEquals(-0.12, session.startLongitude!!, 0.0001)
        assertEquals(14.5, session.weatherTempC!!, 0.0001)
        assertEquals(71, session.weatherHumidityPercent)
        assertEquals(3, session.weatherConditionCode)
        assertEquals(1, intervals.size)
    }

    /**
     * Builds the sessions/hr_samples/run_walk_interval_stats tables as they stood at v11 and v12
     * (identical across those two versions), leaving the file's version unset for the caller.
     */
    private fun openLegacyDatabase(): SQLiteDatabase {
        val rawDb = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
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
        return rawDb
    }

    /** Builds the track_points table exactly as MIGRATION_11_12 leaves it, for v12+ start states. */
    private fun createTrackPointsTable(rawDb: SQLiteDatabase) {
        rawDb.execSQL(
            """
            CREATE TABLE `track_points` (
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
        rawDb.execSQL("CREATE INDEX `index_track_points_sessionId` ON `track_points` (`sessionId`)")
    }

    private fun insertLegacySession(
        rawDb: SQLiteDatabase,
        id: Long,
        zone1Seconds: Long = 0,
        zone2Seconds: Long = 0,
        zone3Seconds: Long = 0,
        zone4Seconds: Long = 0,
        zone5Seconds: Long = 0,
        noDataSeconds: Long = 0,
        sessionType: String = "Run/Walk",
        isRunWalkMode: Int = 0
    ) {
        rawDb.execSQL(
            "INSERT INTO sessions (id, startTime, endTime, durationSeconds, avgBpm, maxBpm, timeInTargetZoneSeconds, " +
                "zone1Seconds, zone2Seconds, zone3Seconds, zone4Seconds, zone5Seconds, runMode, distanceKm, " +
                "avgPaceMinPerKm, noDataSeconds, walkBreaksCount, isRunWalkMode, sessionType, includeInAiTraining) " +
                "VALUES ($id, ${id * 1_000_000}, 0, 0, 0, 0, 0, $zone1Seconds, $zone2Seconds, $zone3Seconds, " +
                "$zone4Seconds, $zone5Seconds, 'treadmill', 0.0, 0.0, $noDataSeconds, 0, $isRunWalkMode, '$sessionType', 1)"
        )
    }

    private fun insertSample(rawDb: SQLiteDatabase, sessionId: Long, elapsedSeconds: Int, rawBpm: Int) {
        rawDb.execSQL(
            "INSERT INTO hr_samples (sessionId, elapsedSeconds, rawBpm, smoothedBpm, connectionState) " +
                "VALUES ($sessionId, $elapsedSeconds, $rawBpm, $rawBpm, 'Connected')"
        )
    }

    private fun <T> runBlockingGet(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}
