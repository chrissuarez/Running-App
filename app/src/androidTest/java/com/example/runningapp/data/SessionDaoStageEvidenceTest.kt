package com.example.runningapp.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a Stage may be graduated on (#234): the Runs recorded under that Stage, and nothing else.
 *
 * The rule lives entirely in the query's `WHERE`, so a mocked DAO cannot show it — a mock will
 * cheerfully hand back a Stage 1 Run when asked for Stage 2's, which is the bug itself.
 */
@RunWith(AndroidJUnit4::class)
class SessionDaoStageEvidenceTest {
    private lateinit var database: AppDatabase
    private lateinit var sessionDao: SessionDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        sessionDao = database.sessionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getLast3AiEligibleRunsOfStage_answersWithThatStagesRunsOnly() {
        runBlocking {
            val stageOneRun = sessionDao.insertSession(runUnder("base_builder", startTime = 1_000L))
            val stageTwoRun = sessionDao.insertSession(runUnder("sub_30_bridge", startTime = 2_000L))
            sessionDao.insertSession(runUnder(null, startTime = 3_000L))

            // The newest Run of all is the one under no Stage, and the one before it belongs to
            // Stage 1 — neither can answer Stage 2, which is the whole of the fix.
            assertEquals(
                listOf(stageTwoRun),
                sessionDao.getLast3AiEligibleRunsOfStage("sub_30_bridge").map { it.id }
            )
            assertEquals(
                listOf(stageOneRun),
                sessionDao.getLast3AiEligibleRunsOfStage("base_builder").map { it.id }
            )
            // The Run carrying no Stage is claimed by nobody: it is evidence for none of them, and
            // there is no query that hands it to one.
            assertEquals(emptyList<Long>(), sessionDao.getLast3AiEligibleRunsOfStage("sub_25_peak").map { it.id })
        }
    }

    @Test
    fun getLast3AiEligibleRunsOfStage_keepsTheOtherThreeConditions() {
        runBlocking {
            val eligible = sessionDao.insertSession(runUnder("base_builder", startTime = 1_000L))
            // Still recording, too short to mean anything, and opted out of AI — each excluded
            // before this ticket and still excluded now.
            sessionDao.insertSession(runUnder("base_builder", startTime = 2_000L).copy(endTime = 0L))
            sessionDao.insertSession(runUnder("base_builder", startTime = 3_000L).copy(durationSeconds = 60))
            sessionDao.insertSession(
                runUnder("base_builder", startTime = 4_000L).copy(includeInAiTraining = false)
            )

            assertEquals(
                listOf(eligible),
                sessionDao.getLast3AiEligibleRunsOfStage("base_builder").map { it.id }
            )
        }
    }

    @Test
    fun getLast3AiEligibleRunsOfStage_takesTheThreeNewestOfTheStage() {
        runBlocking {
            val ids = (1..4).map { runUnder("base_builder", startTime = it * 1_000L) }
                .map { sessionDao.insertSession(it) }

            assertEquals(
                ids.takeLast(3).reversed(),
                sessionDao.getLast3AiEligibleRunsOfStage("base_builder").map { it.id }
            )
        }
    }

    private fun runUnder(stageId: String?, startTime: Long) = RunnerSession(
        startTime = startTime,
        endTime = startTime + 1_000L,
        durationSeconds = 1_500L,
        includeInAiTraining = true,
        ranUnderStageId = stageId,
    )
}
