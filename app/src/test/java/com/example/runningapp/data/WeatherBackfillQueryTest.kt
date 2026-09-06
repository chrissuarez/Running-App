package com.example.runningapp.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The work list the weather backfill walks, against a real SQLite database held in memory (#81).
 *
 * The whole of the ticket is in this read. Which Runs are owed weather, and where each one was run
 * from, is a question about *rows* — a Run recorded before v11 has no start position on it at all
 * and carries its first fix in `track_points` instead — and a hand-written stand-in DAO would agree
 * with whatever it was written to agree with. So the statement the phone runs is the statement run
 * here ([RUNS_OWED_WEATHER_SQL]).
 */
class WeatherBackfillQueryTest {

    private lateinit var db: Connection

    private val firstMorning = 1_700_000_000_000L
    private val aDay = 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.exec("PRAGMA foreign_keys=ON")
        db.exec(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startTime INTEGER NOT NULL,
                endTime INTEGER NOT NULL,
                runMode TEXT NOT NULL,
                startLatitude REAL,
                startLongitude REAL,
                weatherTempC REAL
            )
            """
        )
        db.exec(
            """
            CREATE TABLE track_points (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                timestampMillis INTEGER NOT NULL,
                FOREIGN KEY (sessionId) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a run that recorded its start position is asked about at that position`() {
        givenRun(id = 1, day = 0, startLatitude = 51.5, startLongitude = -0.12)

        val owed = runsOwedWeather()

        assertEquals(1, owed.size)
        assertEquals(1L, owed[0].sessionId)
        assertEquals(51.5, owed[0].latitude, 0.0)
        assertEquals(-0.12, owed[0].longitude, 0.0)
        assertEquals(firstMorning, owed[0].startTime)
    }

    @Test
    fun `a run recorded before start positions existed is asked about at its first fix`() {
        // Every Run recorded before the v11 upgrade has no start position on the row. Its GPS is in
        // `track_points`, where the v12 upgrade put the old hr_samples breadcrumbs — so this is the
        // whole of Chris's older history, and the reason #81 is not already done by #79's retry.
        givenRun(id = 1, day = 0, startLatitude = null, startLongitude = null)
        givenFix(sessionId = 1, atMillis = firstMorning + 60_000, latitude = 51.6, longitude = -0.2)
        givenFix(sessionId = 1, atMillis = firstMorning + 30_000, latitude = 51.5, longitude = -0.1)

        val owed = runsOwedWeather()

        assertEquals(1, owed.size)
        // The earliest fix, not the first row written: the two disagree here on purpose.
        assertEquals(51.5, owed[0].latitude, 0.0)
        assertEquals(-0.1, owed[0].longitude, 0.0)
    }

    @Test
    fun `a run with no position anywhere is skipped`() {
        givenRun(id = 1, day = 0, startLatitude = null, startLongitude = null)

        assertTrue(runsOwedWeather().isEmpty())
    }

    @Test
    fun `a run holding half a start position is asked about at its first fix, not at half of each`() {
        // A latitude from the row and a longitude from the track would name somewhere neither of
        // them is. The pair is taken from one place or from the other, never one of each.
        givenRun(id = 1, day = 0, startLatitude = 51.5, startLongitude = null)
        givenFix(sessionId = 1, atMillis = firstMorning, latitude = 40.0, longitude = -3.7)

        val owed = runsOwedWeather()

        assertEquals(1, owed.size)
        assertEquals(40.0, owed[0].latitude, 0.0)
        assertEquals(-3.7, owed[0].longitude, 0.0)
    }

    @Test
    fun `a run that already has its weather is never asked about again`() {
        givenRun(id = 1, day = 0, startLatitude = 51.5, startLongitude = -0.12, weatherTempC = 14.0)

        assertTrue(runsOwedWeather().isEmpty())
    }

    @Test
    fun `a treadmill run is skipped`() {
        givenRun(id = 1, day = 0, startLatitude = 51.5, startLongitude = -0.12, runMode = "treadmill")

        assertTrue(runsOwedWeather().isEmpty())
    }

    @Test
    fun `a run still being recorded is skipped`() {
        db.exec(
            "INSERT INTO sessions (id, startTime, endTime, runMode, startLatitude, startLongitude, weatherTempC) " +
                "VALUES (1, $firstMorning, 0, 'outdoor', 51.5, -0.12, NULL)"
        )

        assertTrue(runsOwedWeather().isEmpty())
    }

    @Test
    fun `the newest run is asked about first`() {
        givenRun(id = 1, day = 0, startLatitude = 51.5, startLongitude = -0.12)
        givenRun(id = 2, day = 30, startLatitude = 51.5, startLongitude = -0.12)
        givenRun(id = 3, day = 15, startLatitude = 51.5, startLongitude = -0.12)

        // The Run the runner is likeliest to open next is the one that fills first, because a pass
        // over a long history is minutes of fetching and it may be stopped at any point in them.
        assertEquals(listOf(2L, 3L, 1L), runsOwedWeather().map { it.sessionId })
    }

    // -- Writing the rows ---------------------------------------------------------------------

    private fun givenRun(
        id: Long,
        day: Long,
        startLatitude: Double?,
        startLongitude: Double?,
        weatherTempC: Double? = null,
        runMode: String = "outdoor",
    ) {
        val start = firstMorning + day * aDay
        db.exec(
            "INSERT INTO sessions (id, startTime, endTime, runMode, startLatitude, startLongitude, weatherTempC) " +
                "VALUES ($id, $start, ${start + 1800_000}, '$runMode', ${startLatitude.orNull()}, " +
                "${startLongitude.orNull()}, ${weatherTempC.orNull()})"
        )
    }

    private fun givenFix(sessionId: Long, atMillis: Long, latitude: Double, longitude: Double) {
        db.exec(
            "INSERT INTO track_points (sessionId, latitude, longitude, timestampMillis) " +
                "VALUES ($sessionId, $latitude, $longitude, $atMillis)"
        )
    }

    /** The DAO's own read ([RUNS_OWED_WEATHER_SQL]), run against the real tables. */
    private fun runsOwedWeather(): List<WeatherFillTarget> =
        db.createStatement().use { statement ->
            statement.executeQuery(RUNS_OWED_WEATHER_SQL).use { cursor ->
                buildList {
                    while (cursor.next()) {
                        add(
                            WeatherFillTarget(
                                sessionId = cursor.getLong("sessionId"),
                                startTime = cursor.getLong("startTime"),
                                latitude = cursor.getDouble("latitude"),
                                longitude = cursor.getDouble("longitude"),
                            )
                        )
                    }
                }
            }
        }

    private fun Double?.orNull(): String = this?.toString() ?: "NULL"

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
}
