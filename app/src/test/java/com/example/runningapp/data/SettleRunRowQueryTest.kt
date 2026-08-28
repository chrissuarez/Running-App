package com.example.runningapp.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The statement that settles a Run's row (#315, #382), against a real SQLite database held in
 * memory.
 *
 * It is run here rather than checked through a fake DAO because what is being proved is not that a
 * settler asks the right question — it is that the question and the write are *one* statement. A
 * Run has two settlers that never observe each other: its own finalize, running from the session
 * thread's dispatch of a STOP, and the teardown's rescue, running from `onDestroy` on main. Every
 * wait between them is bounded, so a check taken before a write is a decision about a row that can
 * change in between, and both settlers can pass it. Only the statement itself can show there is no
 * such in-between, and only a real engine can run it.
 *
 * The rule it keeps is that **the Run's row is settled by the write that finds it unsettled**. Every
 * in-memory approximation of that rule has lost Runs: refusing the finalize during a teardown left
 * the row with no writer at all, and a claim both settlers raced for left it with no writer again
 * as soon as the winner turned out to have nothing to rebuild. The condition travels with the write
 * here, where nothing can get between them.
 */
class SettleRunRowQueryTest {

    private lateinit var db: Connection

    private val startedAt = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // The settling columns, and beside them the four the runner writes from the feel sheet
        // while a finalize is still waiting out the recorder's tail writes (#317). Those four are
        // here to be shown surviving: a settler may only write what it measured.
        db.exec(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startTime INTEGER NOT NULL,
                endTime INTEGER NOT NULL DEFAULT 0,
                durationSeconds INTEGER NOT NULL DEFAULT 0,
                avgBpm INTEGER NOT NULL DEFAULT 0,
                maxBpm INTEGER NOT NULL DEFAULT 0,
                distanceKm REAL NOT NULL DEFAULT 0,
                avgPaceMinPerKm REAL NOT NULL DEFAULT 0,
                noDataSeconds INTEGER NOT NULL DEFAULT 0,
                zone1Seconds INTEGER NOT NULL DEFAULT 0,
                zone2Seconds INTEGER NOT NULL DEFAULT 0,
                zone3Seconds INTEGER NOT NULL DEFAULT 0,
                zone4Seconds INTEGER NOT NULL DEFAULT 0,
                zone5Seconds INTEGER NOT NULL DEFAULT 0,
                effortScore INTEGER,
                walkBreaksCount INTEGER NOT NULL DEFAULT 0,
                isRunWalkMode INTEGER NOT NULL DEFAULT 0,
                startLatitude REAL,
                startLongitude REAL,
                stageSettled INTEGER NOT NULL DEFAULT 0,
                isWalk INTEGER NOT NULL DEFAULT 0,
                perceivedEffort INTEGER,
                sessionNote TEXT,
                bandedOnMaxHr INTEGER
            )
            """
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `the settler that finds the row unfinished is the one that settles it`() {
        givenRunStillRecording(67L)

        assertEquals(1, settle(67L, durationSeconds = 292, endTime = startedAt + 292_000))

        assertEquals(startedAt + 292_000, valueOf(67L, "endTime"))
        assertEquals(292L, valueOf(67L, "durationSeconds"))
    }

    @Test
    fun `a second settler changes nothing, and is told it changed nothing`() {
        // The two settlers of one Run, in the order the race can leave them. The first writes the
        // totals; the second arrives with its own answer and must not put it over the top of them,
        // which is the two-writer harm #315 was filed about — and must be *told*, because
        // everything that hangs off a Run being finished belongs to whoever finished it.
        givenRunStillRecording(67L)

        assertEquals(1, settle(67L, durationSeconds = 292, endTime = startedAt + 292_000))
        assertEquals(0, settle(67L, durationSeconds = 41, endTime = startedAt + 41_000))

        assertEquals(292L, valueOf(67L, "durationSeconds"))
        assertEquals(startedAt + 292_000, valueOf(67L, "endTime"))
    }

    @Test
    fun `a rescue that rebuilt nothing leaves the row for the Run's own finalize`() {
        // The Run this pass was filed about: a short strapless treadmill Run, torn down, with no
        // sample and no fix to rebuild it from. Its rescue writes nothing at all — the statement is
        // never reached — and the row is therefore still unfinished when the Run's own finalize
        // gets to it, whatever either of them thought about who was going to settle it.
        //
        // Under the claim this replaces, the teardown had taken the row before it knew any of that
        // and the finalize had already stood down. Nobody wrote, and the row stayed at
        // `endTime = 0`: a Run gone from history, the export and the coach.
        givenRunStillRecording(67L)

        // ... the rescue finds nothing to write and writes nothing ...

        assertEquals(1, settle(67L, durationSeconds = 41, endTime = startedAt + 41_000))
        assertEquals(startedAt + 41_000, valueOf(67L, "endTime"))
    }

    @Test
    fun `what the runner wrote while the finalize waited is not a settler's to write`() {
        // The Walk mark, the effort and the note are the runner's, written from the feel sheet in
        // the seconds a finalize spends waiting out the recorder's tail writes (#317). A settling
        // write that carried the whole row would undo them — and the settlement that follows would
        // then judge the Run off the `isWalk = 0` it had just restored, which is a Stage graduated
        // on a walk and cannot be taken back. Naming the columns is what makes that unreachable
        // rather than a matter of when the row was read.
        givenRunStillRecording(67L)
        db.exec("UPDATE sessions SET isWalk = 1, perceivedEffort = 4, sessionNote = 'legs heavy', bandedOnMaxHr = 181 WHERE id = 67")

        assertEquals(1, settle(67L, durationSeconds = 292, endTime = startedAt + 292_000))

        assertEquals(1L, valueOf(67L, "isWalk"))
        assertEquals(4L, valueOf(67L, "perceivedEffort"))
        assertEquals(181L, valueOf(67L, "bandedOnMaxHr"))
        assertEquals("legs heavy", db.queryText("SELECT sessionNote FROM sessions WHERE id = 67"))
    }

    @Test
    fun `a row that is not there at all is nothing to settle`() {
        assertEquals(0, settle(67L, durationSeconds = 292, endTime = startedAt + 292_000))
    }

    @Test
    fun `only the Run named is settled`() {
        givenRunStillRecording(67L)
        givenRunStillRecording(68L)

        assertEquals(1, settle(67L, durationSeconds = 292, endTime = startedAt + 292_000))

        assertEquals(0L, valueOf(68L, "endTime"))
    }

    @Test
    fun `a rescue's mark takes the Run out of the launch pass's reach`() {
        // The rescue settles the Stage question by writing it closed, because the Run it is
        // rescuing is a Run nobody closed and the graduation rule may not judge one of those
        // (ADR 0016, [finishedFromRecord]). This is that mark doing its job: the launch pass looks
        // for a finished Run still owing a settlement, and this Run is not one.
        givenRunStillRecording(67L)

        assertEquals(1, settle(67L, durationSeconds = 292, endTime = startedAt + 292_000, stageSettled = true))

        assertEquals(emptyList<Long>(), runsOwingAStageSettlement())
    }

    @Test
    fun `the Run's own finish hands the Stage question back after losing the row`() {
        // The one case the rescue's mark is wrong about (#383). The runner stopped this Run
        // themselves, so its own finalize is on its way — but the teardown's bounded joins gave up
        // while that dispatch was still in flight, the rescue wrote first, and the finalize is told
        // it lost the row. The rescue could not know any of that: it sees the row and the record,
        // and neither says a runner ever closed this Run.
        //
        // The finalize does know, and this is what it does about it. It leaves the rescue's totals
        // exactly where they are — they are a true account of the seconds that reached the database
        // — and reopens the one thing the rescue decided on a premise that was false. The launch
        // pass then has the Run, which is what a rescued Run its runner closed should always have
        // got.
        givenRunStillRecording(67L)
        assertEquals(1, settle(67L, durationSeconds = 292, endTime = startedAt + 292_000, stageSettled = true))

        assertEquals(1, handTheStageQuestionBack(67L))

        assertEquals(listOf(67L), runsOwingAStageSettlement())
        // And nothing else about the row moved: the settler that won it keeps every total it wrote.
        assertEquals(292L, valueOf(67L, "durationSeconds"))
        assertEquals(startedAt + 292_000, valueOf(67L, "endTime"))
    }

    @Test
    fun `handing the question back names one Run and no other`() {
        givenRunStillRecording(67L)
        givenRunStillRecording(68L)
        assertEquals(1, settle(67L, durationSeconds = 292, endTime = startedAt + 292_000, stageSettled = true))
        assertEquals(1, settle(68L, durationSeconds = 41, endTime = startedAt + 41_000, stageSettled = true))

        assertEquals(1, handTheStageQuestionBack(67L))

        assertEquals(listOf(67L), runsOwingAStageSettlement())
    }

    /** The statement the losing finalize runs, run here — the constant the DAO is annotated with. */
    private fun handTheStageQuestionBack(sessionId: Long): Int =
        db.prepareStatement(HAND_THE_STAGE_QUESTION_BACK.replace(Regex(":\\w+"), "?")).use {
            it.setLong(1, sessionId)
            it.executeUpdate()
        }

    /** The question the launch pass asks, run here — the constant the DAO is annotated with. */
    private fun runsOwingAStageSettlement(): List<Long> = db.query(RUNS_OWING_A_STAGE_SETTLEMENT)

    /**
     * The statement the phone runs, run here — the same constant the DAO is annotated with, with
     * Room's named parameters swapped for JDBC's positional ones in the order they appear.
     *
     * The values are one settler's finished answer for the Run, exactly as [settleRunRow] takes
     * them off it.
     */
    private fun settle(
        sessionId: Long,
        endTime: Long,
        durationSeconds: Long,
        stageSettled: Boolean = false,
    ): Int = db.prepareStatement(SETTLE_RUN_ROW_IF_UNSETTLED.replace(Regex(":\\w+"), "?")).use {
        it.setLong(1, endTime)
        it.setLong(2, durationSeconds)
        it.setInt(3, 130)
        it.setInt(4, 156)
        it.setDouble(5, 4.2)
        it.setDouble(6, 5.8)
        it.setLong(7, 0L)
        it.setLong(8, 0L)
        it.setLong(9, durationSeconds)
        it.setLong(10, 0L)
        it.setLong(11, 0L)
        it.setLong(12, 0L)
        it.setInt(13, 44)
        it.setInt(14, 0)
        it.setBoolean(15, false)
        it.setDouble(16, 51.5)
        it.setDouble(17, -0.12)
        it.setBoolean(18, stageSettled)
        it.setLong(19, sessionId)
        it.executeUpdate()
    }

    private fun givenRunStillRecording(id: Long) =
        db.exec("INSERT INTO sessions (id, startTime, endTime) VALUES ($id, $startedAt, 0)")

    private fun valueOf(id: Long, column: String): Long =
        db.query("SELECT $column FROM sessions WHERE id = $id").single()

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.query(sql: String): List<Long> = createStatement().use { statement ->
        statement.executeQuery(sql).use {
            buildList { while (it.next()) add(it.getLong(1)) }
        }
    }

    private fun Connection.queryText(sql: String): String? = createStatement().use { statement ->
        statement.executeQuery(sql).use { if (it.next()) it.getString(1) else null }
    }
}
