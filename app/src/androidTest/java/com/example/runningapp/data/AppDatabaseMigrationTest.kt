package com.example.runningapp.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.runningapp.HrProfile
import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.training.GoalMetric
import com.example.runningapp.training.GoalPeriod
import kotlinx.coroutines.flow.first
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
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
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
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
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
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
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
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
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

    @Test
    fun migrate16To17_leavesExistingTrackPointsWithNoPauseBoundary() {
        // Where a pause fell was never written down before v17 and cannot be recovered, so every
        // existing point must come through saying only that: not a resume. Claiming otherwise would
        // break an old run's route somewhere nothing happened.
        val rawDb = openLegacyDatabase()
        rawDb.execSQL(
            "INSERT INTO sessions (id, startTime, endTime, durationSeconds, avgBpm, maxBpm, timeInTargetZoneSeconds, " +
                "zone1Seconds, zone2Seconds, zone3Seconds, zone4Seconds, zone5Seconds, runMode, distanceKm, " +
                "avgPaceMinPerKm, noDataSeconds, walkBreaksCount, isRunWalkMode, sessionType, includeInAiTraining) " +
                "VALUES (1, 1000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 'outdoor', 0.0, 0.0, 0, 0, 0, 'Run/Walk', 1)"
        )
        rawDb.execSQL("INSERT INTO hr_samples (sessionId, elapsedSeconds, rawBpm, smoothedBpm, connectionState, latitude, longitude) VALUES (1, 1, 120, 120, 'Connected', 40.0, -70.0)")
        rawDb.version = 11
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
            .build()
        val trackPoints = runBlockingGet { migratedDb.trackPointDao().getTrackPointsForSessionOnce(1) }
        migratedDb.close()

        assertEquals(1, trackPoints.size)
        assertEquals(false, trackPoints.single().startsAfterPause)
    }

    @Test
    fun migrate17To19_bringsTheOtherBranchesV17ToTheSameShape_keepingItsMovingTimes() {
        // A v17 built by the #163 pace branch reached the phone before #84 spent v17 on
        // startsAfterPause: sessions carries movingTimeSeconds, track_points has no boundary column,
        // and the version number says 17 for both. Room refuses a database carrying a column its
        // entities do not declare, so without this the phone cannot open this build at all.
        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
            .build()
        runBlockingGet { migratedDb.sessionDao().insertSession(RunnerSession(startTime = 1_000L, endTime = 2_000L)) }
        migratedDb.close()

        // Rewind to the shape the other branch left behind, version number and all. movingTimeSeconds
        // is already there and is already the right shape — that is the whole point of it — so the
        // rewind only has to take the boundary column back off and put the number back.
        val rawDb = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        rawDb.execSQL("ALTER TABLE track_points DROP COLUMN startsAfterPause")
        rawDb.execSQL("UPDATE sessions SET movingTimeSeconds = 1234")
        rawDb.version = 17
        rawDb.close()

        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
            .build()
        // Opening at all is the assertion on shape: Room validates every table against today's
        // entities, so it passes only if the boundary column is back. The run itself must still be
        // there — a schema this mismatched is no reason to lose history — and so must its moving
        // time, which this build measures the same way the one that stored it did.
        val session = runBlockingGet { reopened.sessionDao().getSessionById(1) }!!
        val trackPoints = runBlockingGet { reopened.trackPointDao().getTrackPointsForSessionOnce(1) }
        reopened.close()

        assertEquals(1_000L, session.startTime)
        assertEquals(1234L, session.movingTimeSeconds)
        assertEquals(emptyList<TrackPoint>(), trackPoints)
    }

    @Test
    fun migrate18To19_addsMovingTimeAsNull_leavingItForTheBackfillToMeasure() {
        val rawDb = openLegacyDatabase()
        createTrackPointsTable(rawDb)
        insertLegacySession(rawDb, id = 1)
        rawDb.execSQL("UPDATE sessions SET endTime = 9000, durationSeconds = 2259, runMode = 'outdoor', distanceKm = 4.53 WHERE id = 1")
        rawDb.version = 12
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
            .build()
        val session = runBlockingGet { migratedDb.sessionDao().getSessionById(1) }!!
        val needingBackfill = runBlockingGet { migratedDb.sessionDao().getSessionIdsMissingMovingTime() }
        migratedDb.close()

        // Null, not zero: the migration cannot measure a track, so it says "not measured yet"
        // rather than "this run never moved", and the run's pace falls back to its duration until
        // the backfill reaches it.
        assertEquals(null, session.movingTimeSeconds)
        assertEquals(2259L, session.durationSeconds)
        assertEquals(listOf(1L), needingBackfill)
    }

    @Test
    fun migrate19To20_opensAnEmptyRecordBook_whoseMedalsGoWithTheirRun() {
        val rawDb = openLegacyDatabase()
        createTrackPointsTable(rawDb)
        insertLegacySession(rawDb, id = 1)
        rawDb.version = 12
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
            .build()

        // Opening at all is the assertion on shape: Room validates the new table, its columns and
        // both indices against the entity, so a migration that built it differently fails here.
        val bookAtFirstOpen = runBlockingGet { migratedDb.achievementDao().getAllAchievements() }

        runBlockingGet {
            migratedDb.achievementDao().insertAchievements(
                listOf(
                    Achievement(
                        sessionId = 1,
                        type = RecordType.FASTEST_5K,
                        medal = Medal.GOLD,
                        value = 1_500.0,
                    )
                )
            )
        }
        val held = runBlockingGet { migratedDb.achievementDao().getAllAchievements() }

        // A medal is a recording of a run like any other, so deleting the run takes it too — the
        // cascade the migration declares, exercised rather than trusted.
        runBlockingGet { migratedDb.sessionDao().deleteSessionById(1) }
        val afterTheRunWasDeleted = runBlockingGet { migratedDb.achievementDao().getAllAchievements() }
        migratedDb.close()

        // History recorded before this arrives unscored: filling the book is #50's job.
        assertEquals(emptyList<Achievement>(), bookAtFirstOpen)
        assertEquals(listOf(RecordType.FASTEST_5K to Medal.GOLD), held.map { it.type to it.medal })
        assertEquals(1_500.0, held.single().value, 0.001)
        assertEquals(emptyList<Achievement>(), afterTheRunWasDeleted)
    }

    @Test
    fun migrate20To21_addsEffortAsNull_leavingHistoryUnscoredForTheBackfill() {
        val rawDb = openLegacyDatabase()
        createTrackPointsTable(rawDb)
        insertLegacySession(rawDb, id = 1)
        rawDb.execSQL("UPDATE sessions SET endTime = 9000, durationSeconds = 2259, avgBpm = 141 WHERE id = 1")
        insertSample(rawDb, sessionId = 1, elapsedSeconds = 1, rawBpm = 141)
        rawDb.version = 12
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
            .build()
        val session = runBlockingGet { migratedDb.sessionDao().getSessionById(1) }!!
        migratedDb.close()

        // Null rather than a zero, and rather than a score: the migration adds the column and
        // nothing else. A run already in history has not been scored, which is different from
        // having been scored at nothing, and #62 is what tells the two apart when it backfills.
        assertEquals(null, session.effortScore)
        // Everything the run already had is where it was.
        assertEquals(2259L, session.durationSeconds)
        assertEquals(141, session.avgBpm)
    }

    @Test
    fun migrate21To22_leavesEveryRunOwingAScoring_soTheLaunchPassMeasuresThemAll() {
        val rawDb = openLegacyDatabase()
        createTrackPointsTable(rawDb)
        insertLegacySession(rawDb, id = 1)
        rawDb.execSQL("UPDATE sessions SET endTime = 9000, durationSeconds = 2259 WHERE id = 1")
        rawDb.version = 12
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
            .build()
        val session = runBlockingGet { migratedDb.sessionDao().getSessionById(1) }!!
        val owing = runBlockingGet { migratedDb.sessionDao().getSessionIdsMissingRecordScoring() }
        migratedDb.close()

        // Unscored is the repair, not a side effect: a Run whose scoring was missed before this
        // shipped holds no medals and nothing else would ever give it any (#210). The first launch
        // after the upgrade measures every Run once, and only ever once.
        assertEquals(false, session.recordsScored)
        assertEquals(listOf(1L), owing)
        assertEquals(2259L, session.durationSeconds)
    }

    @Test
    fun migrate22To23_queuesEveryMeasuredRunToBeMeasuredAgain_leavingTreadmillsAlone() {
        val rawDb = openLegacyDatabase()
        createTrackPointsTable(rawDb)
        insertLegacySession(rawDb, id = 1)
        insertLegacySession(rawDb, id = 2)
        insertLegacySession(rawDb, id = 3)
        rawDb.execSQL("UPDATE sessions SET endTime = 9000, durationSeconds = 2259 WHERE id = 1")
        rawDb.execSQL("UPDATE sessions SET endTime = 9000, durationSeconds = 1800, runMode = 'treadmill' WHERE id = 2")
        rawDb.execSQL("UPDATE sessions SET endTime = 0 WHERE id = 3")
        rawDb.version = 12
        rawDb.close()

        // Up to today first, so the column exists to be filled - then measured moving times are
        // written into all three and the file is wound back to v22, which is v23's schema exactly.
        // Reopening therefore runs this migration and only this one, against a history that has
        // already been measured under the old rule.
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
            .build()
            .close()
        val measuredDb = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        measuredDb.execSQL("UPDATE sessions SET movingTimeSeconds = 2100, avgPaceMinPerKm = 6.0")
        measuredDb.version = 22
        measuredDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25)
            .build()
        val queued = runBlockingGet { migratedDb.sessionDao().getSessionIdsMissingMovingTime() }
        val outdoorRun = runBlockingGet { migratedDb.sessionDao().getSessionById(1) }!!
        val treadmillRun = runBlockingGet { migratedDb.sessionDao().getSessionById(2) }!!
        migratedDb.close()

        // The finished outdoor Run is the one the rule changed under (#165), and it goes back into
        // the queue holding nothing. The treadmill Run has no track to measure and the Run still
        // being written measures itself when it ends, so neither is touched.
        assertEquals(listOf(1L), queued)
        assertNull(outdoorRun.movingTimeSeconds)
        assertEquals(2100L, treadmillRun.movingTimeSeconds)
        // Everything the Run already had is where it was: only the derived number was withdrawn.
        assertEquals(2259L, outdoorRun.durationSeconds)
    }

    @Test
    fun migrate23To24_leavesEveryRunWithNoReserveOfItsOwn_soTheyAreReadOnHistorys() {
        val rawDb = openLegacyDatabase()
        createTrackPointsTable(rawDb)
        insertLegacySession(rawDb, id = 1)
        rawDb.execSQL("UPDATE sessions SET endTime = 9000, durationSeconds = 2259 WHERE id = 1")
        rawDb.version = 12
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
            .build()
        val run = runBlockingGet { migratedDb.sessionDao().getSessionById(1) }!!
        migratedDb.close()

        // Null rather than filled in: the numbers a backfill would write live in DataStore, and
        // null already means "banded against whatever history is banded against" (#228) — which is
        // the very pair it would have written.
        assertNull(run.bandedOnMaxHr)
        assertNull(run.bandedOnRestingHr)
        assertNull(run.bandedOnHrProfile())
        // The Run itself is untouched: the migration adds room and nothing else.
        assertEquals(2259L, run.durationSeconds)
        assertEquals(9000L, run.endTime)
    }

    @Test
    fun migrate25To26_addsAnEmptyRouteLibrary_withEveryRunLeftAsItWas() {
        val rawDb = openLegacyDatabase()
        createTrackPointsTable(rawDb)
        insertLegacySession(rawDb, id = 1)
        rawDb.execSQL("UPDATE sessions SET endTime = 9000, durationSeconds = 2259, distanceKm = 5.1 WHERE id = 1")
        insertSample(rawDb, sessionId = 1, elapsedSeconds = 1, rawBpm = 140)
        rawDb.version = 12
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
            .build()
        val routes = runBlockingGet { migratedDb.routeDao().getAllRoutesFlow().first() }
        val run = runBlockingGet { migratedDb.sessionDao().getSessionById(1) }!!
        val samples = runBlockingGet { migratedDb.sampleDao().getSamplesForSessionOnce(1) }

        // Empty, and nothing could have filled it: no earlier version of the app kept a Route, and
        // turning a past Run into one is a thing the runner asks for a Run at a time (#55).
        assertEquals(emptyList<Route>(), routes)

        // And the library can be written to and emptied without a Run noticing — the point of it
        // having no key into `sessions` in either direction (#54).
        runBlockingGet {
            migratedDb.routeDao().insertRoute(
                Route(
                    name = "Regent's Park loop",
                    distanceMeters = 4_200.0,
                    elevationGainMeters = null,
                    polyline = "51.5000000,-0.1000000 51.5010000,-0.1010000",
                    createdAtMillis = 1_700_000_000_000L,
                    source = RouteSource.IMPORTED,
                )
            )
        }
        val stored = runBlockingGet { migratedDb.routeDao().getAllRoutesFlow().first() }.single()
        assertEquals("Regent's Park loop", stored.name)
        assertNull(stored.elevationGainMeters)
        runBlockingGet { migratedDb.routeDao().deleteRoute(stored.id) }
        assertEquals(
            emptyList<Route>(),
            runBlockingGet { migratedDb.routeDao().getAllRoutesFlow().first() },
        )

        migratedDb.close()

        // The Run is exactly where it was, beats and all.
        assertEquals(2259L, run.durationSeconds)
        assertEquals(9000L, run.endTime)
        assertEquals(5.1, run.distanceKm, 0.0001)
        assertEquals(1, samples.size)
        assertEquals(140, samples.single().rawBpm)
    }

    @Test
    fun migrate26To27_addsAnEmptyGoalsTable_withEveryRunLeftAsItWas() {
        val rawDb = openLegacyDatabase()
        createTrackPointsTable(rawDb)
        insertLegacySession(rawDb, id = 1)
        rawDb.execSQL("UPDATE sessions SET endTime = 9000, durationSeconds = 2259, distanceKm = 5.1 WHERE id = 1")
        rawDb.version = 12
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
            .build()
        val goals = runBlockingGet { migratedDb.goalDao().getAllGoalsFlow().first() }
        val run = runBlockingGet { migratedDb.sessionDao().getSessionById(1) }!!

        // Empty, and deliberately: a goal is something the runner states, and reading one off their
        // recent weeks would be the app setting a target on their behalf (#82).
        assertEquals(emptyList<GoalRow>(), goals)

        runBlockingGet {
            migratedDb.goalDao().setGoal(
                GoalRow(
                    period = GoalPeriod.WEEK,
                    metric = GoalMetric.DISTANCE,
                    target = 40.0,
                    createdAtMillis = 1_700_000_000_000L,
                )
            )
        }
        // Stating the same period and metric again is an edit and never a second goal — the unique
        // index is what makes the two indistinguishable.
        runBlockingGet {
            migratedDb.goalDao().setGoal(
                GoalRow(
                    period = GoalPeriod.WEEK,
                    metric = GoalMetric.DISTANCE,
                    target = 50.0,
                    createdAtMillis = 1_700_000_001_000L,
                )
            )
        }
        val stored = runBlockingGet { migratedDb.goalDao().getAllGoalsFlow().first() }.single()
        assertEquals(50.0, stored.target, 0.0001)
        assertEquals(GoalPeriod.WEEK, stored.period)

        // And emptying it leaves the Run where it was — no key into `sessions` in either direction.
        runBlockingGet { migratedDb.goalDao().deleteGoal(stored.id) }
        assertEquals(
            emptyList<GoalRow>(),
            runBlockingGet { migratedDb.goalDao().getAllGoalsFlow().first() },
        )
        migratedDb.close()

        assertEquals(2259L, run.durationSeconds)
        assertEquals(9000L, run.endTime)
        assertEquals(5.1, run.distanceKm, 0.0001)
    }

    @Test
    fun migrate27To28_addsAnEmptyStatedBestEffortTable_andAStatementGoesWithItsRun() {
        val rawDb = openLegacyDatabase()
        createTrackPointsTable(rawDb)
        insertLegacySession(rawDb, id = 1)
        rawDb.execSQL("UPDATE sessions SET endTime = 9000, durationSeconds = 2259, distanceKm = 5.1 WHERE id = 1")
        rawDb.version = 12
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
            .build()
        val dao = migratedDb.statedBestEffortDao()
        val run = runBlockingGet { migratedDb.sessionDao().getSessionById(1) }!!

        // Empty, and nothing could have filled it: before #282 there was no way to state one, and
        // reading one off a Run's distance and duration is the derivation ADR 0015 refuses.
        assertEquals(emptyList<StatedBestEffort>(), runBlockingGet { dao.getAll() })

        runBlockingGet {
            dao.state(StatedBestEffort(sessionId = 1, type = RecordType.FASTEST_5K, seconds = 1_500))
        }
        // A second distance from the same Run is a second claim: a console shows lap times, and the
        // 5 km says nothing about the 1 km.
        runBlockingGet {
            dao.state(StatedBestEffort(sessionId = 1, type = RecordType.FASTEST_1K, seconds = 280))
        }
        assertEquals(2, runBlockingGet { dao.getForSession(1) }.size)

        // Stating the same distance again is a correction and never a second claim about the same
        // stretch — the unique index is what makes the two indistinguishable.
        runBlockingGet {
            dao.state(StatedBestEffort(sessionId = 1, type = RecordType.FASTEST_5K, seconds = 1_440))
        }
        val fiveK = runBlockingGet { dao.getForSession(1) }.single { it.type == RecordType.FASTEST_5K }
        assertEquals(1_440, fiveK.seconds)

        runBlockingGet { dao.withdraw(1, RecordType.FASTEST_1K) }
        assertEquals(1, runBlockingGet { dao.getForSession(1) }.size)

        // And a claim is part of its Run: deleting the Run takes it, so no orphan goes on holding a
        // place in the record book.
        runBlockingGet { migratedDb.sessionDao().deleteSessionsByIds(listOf(1L)) }
        assertEquals(emptyList<StatedBestEffort>(), runBlockingGet { dao.getAll() })

        migratedDb.close()

        // The Run was exactly where it was until it was deleted: the migration adds room, nothing else.
        assertEquals(2259L, run.durationSeconds)
        assertEquals(9000L, run.endTime)
        assertEquals(5.1, run.distanceKm, 0.0001)
    }

    @Test
    fun migrate24To25_leavesEveryRunUnderNoStage_soHistoryGraduatesNothing() {
        val rawDb = openLegacyDatabase()
        createTrackPointsTable(rawDb)
        insertLegacySession(rawDb, id = 1)
        rawDb.execSQL("UPDATE sessions SET endTime = 9000, durationSeconds = 2259 WHERE id = 1")
        rawDb.version = 12
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
            .build()
        val run = runBlockingGet { migratedDb.sessionDao().getSessionById(1) }!!
        val evidence = runBlockingGet { migratedDb.sessionDao().getLast3AiEligibleRunsOfStage("base_builder") }
        migratedDb.close()

        // Null rather than filled in with whichever Stage the runner is on today: that guess is
        // exactly what lets one Stage's running graduate the next one too (#234). So a Run recorded
        // before the column existed answers no Stage's requirement, and the coach is shown none of
        // them — which errs towards graduating late rather than twice.
        assertNull(run.ranUnderStageId)
        assertEquals(emptyList<RunnerSession>(), evidence)
        // The Run itself is untouched: the migration adds room and nothing else.
        assertEquals(2259L, run.durationSeconds)
        assertEquals(9000L, run.endTime)
    }

    @Test
    fun migrate29To30_leavesEveryRunUnderNoWorkout_soHistoryHoldsNoTest() {
        val rawDb = openLegacyDatabase()
        createTrackPointsTable(rawDb)
        insertLegacySession(rawDb, id = 1)
        rawDb.execSQL("UPDATE sessions SET endTime = 9000, durationSeconds = 2259 WHERE id = 1")
        rawDb.version = 12
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*appDatabaseMigrations { HrProfile(190) })
            .build()
        val run = runBlockingGet { migratedDb.sessionDao().getSessionById(1) }!!
        val lastTest = runBlockingGet {
            migratedDb.sessionDao().getLastCompletedRunStartOfWorkout("w3_s2").first()
        }
        migratedDb.close()

        // Null rather than guessed at (#292): a past Run wrongly called a Test would silence the
        // three-week prompt for three weeks, so history simply holds no Test and the first one the
        // runner runs from here starts the clock.
        assertNull(run.ranUnderWorkoutId)
        assertNull(lastTest)
        // The Run itself is untouched: the migration adds room and nothing else.
        assertEquals(2259L, run.durationSeconds)
        assertEquals(9000L, run.endTime)
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
