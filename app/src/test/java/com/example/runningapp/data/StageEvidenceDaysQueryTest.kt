package com.example.runningapp.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The query behind the Stage's training record (#289) — `getAiEvidenceRunDaysOfStage` — against a
 * real SQLite database held in memory.
 *
 * What it has to prove is that this query and the graduation guard agree about what a *qualifying*
 * Run is. The guard asks `SessionRepository.isStageEvidence` of the three Runs the coach was shown;
 * this asks the same question of the whole Stage, in SQL. Two filters written in two languages is
 * exactly the pair that drifts, and the drift would be a count telling the coach a Stage holds
 * evidence the guard would then refuse to graduate on.
 *
 * Run against a real engine because the six conditions are the whole of the query — a fake DAO
 * would only prove the repository called something.
 */
class StageEvidenceDaysQueryTest {

    private lateinit var db: Connection

    /** The statement as `SessionDao.getAiEvidenceRunDaysOfStage` declares it. */
    private val evidenceDays = """
        SELECT id, startTime FROM sessions
        WHERE endTime > 0
          AND durationSeconds > 120
          AND includeInAiTraining = 1
          AND ranUnderStageId = 'base_builder'
          AND isRunWalkMode = 1
          AND isWalk = 0
        ORDER BY startTime ASC
    """

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.exec(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startTime INTEGER NOT NULL,
                endTime INTEGER NOT NULL DEFAULT 0,
                durationSeconds INTEGER NOT NULL DEFAULT 0,
                includeInAiTraining INTEGER NOT NULL DEFAULT 1,
                ranUnderStageId TEXT,
                isRunWalkMode INTEGER NOT NULL DEFAULT 0,
                isWalk INTEGER NOT NULL DEFAULT 0,
                ranAtUtcOffsetSeconds INTEGER
            )
            """
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `every qualifying run of the stage is counted, oldest first and with no limit`() {
        // Five, which is more than the three the coach is shown — the whole reason this query
        // exists. They go in newest first to prove the ordering is the query's and not the table's.
        (5 downTo 1).forEach { givenRun(id = it.toLong(), startTime = it * 1_000L) }

        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L), db.query(evidenceDays))
    }

    @Test
    fun `a walk is not evidence, however it was recorded`() {
        givenRun(id = 1, startTime = 1_000)
        givenRun(id = 2, startTime = 2_000, isWalk = true)

        assertEquals(listOf(1_000L), db.query(evidenceDays))
    }

    @Test
    fun `an unplanned open run followed no structure, so it answers nothing`() {
        givenRun(id = 1, startTime = 1_000)
        givenRun(id = 2, startTime = 2_000, isRunWalkMode = false)

        assertEquals(listOf(1_000L), db.query(evidenceDays))
    }

    @Test
    fun `another stage's runs, unfinished runs, short runs and opted-out runs are all left out`() {
        givenRun(id = 1, startTime = 1_000)
        givenRun(id = 2, startTime = 2_000, stageId = "sub_30_bridge")
        givenRun(id = 3, startTime = 3_000, stageId = null)
        givenRun(id = 4, startTime = 4_000, endTime = 0)
        givenRun(id = 5, startTime = 5_000, durationSeconds = 120)
        givenRun(id = 6, startTime = 6_000, includeInAiTraining = false)

        assertEquals(listOf(1_000L), db.query(evidenceDays))
    }

    private fun givenRun(
        id: Long,
        startTime: Long,
        endTime: Long = startTime + 1,
        durationSeconds: Long = 1_800,
        includeInAiTraining: Boolean = true,
        stageId: String? = "base_builder",
        isRunWalkMode: Boolean = true,
        isWalk: Boolean = false,
    ) = db.exec(
        """
        INSERT INTO sessions
            (id, startTime, endTime, durationSeconds, includeInAiTraining, ranUnderStageId,
             isRunWalkMode, isWalk)
        VALUES
            ($id, $startTime, $endTime, $durationSeconds, ${includeInAiTraining.asInt},
             ${stageId?.let { "'$it'" } ?: "NULL"}, ${isRunWalkMode.asInt}, ${isWalk.asInt})
        """
    )

    private val Boolean.asInt: Int get() = if (this) 1 else 0

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    /** The startTime column, which is the whole of what the record reads — the id is column 1. */
    private fun Connection.query(sql: String): List<Long> = createStatement().use { statement ->
        statement.executeQuery(sql).use {
            buildList { while (it.next()) add(it.getLong(2)) }
        }
    }
}
