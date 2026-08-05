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
 * The one-shot zone retally (#112) reads its work list from [SessionDao.getFinalizedSessionIds],
 * and a run in progress must not be on it: the recorder finalizes that row from its own in-memory
 * counters, so retallying it would be overwritten while still spending the flag.
 *
 * Mocking the DAO cannot show this — the exclusion lives in the query's `WHERE`, so it takes a
 * real database.
 */
@RunWith(AndroidJUnit4::class)
class SessionDaoFinalizedIdsTest {
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
    fun getFinalizedSessionIds_omitsTheRunStillInProgress() {
        runBlocking {
            val finished = sessionDao.insertSession(session(startTime = 1_000L, endTime = 2_000L))
            val alsoFinished = sessionDao.insertSession(session(startTime = 3_000L, endTime = 4_000L))
            // endTime 0 is what "still recording" looks like, as elsewhere in this DAO.
            sessionDao.insertSession(session(startTime = 5_000L, endTime = 0L))

            assertEquals(
                listOf(finished, alsoFinished),
                sessionDao.getFinalizedSessionIds().sorted()
            )
        }
    }

    /**
     * The re-tally re-scores what it re-bands, but only where there is a Score to move (#61). The
     * guard is a `CASE` inside the statement, so — like the `WHERE` above — a mock cannot show it.
     */
    @Test
    fun updateZoneSecondsAndEffort_movesAScoreThatExistsAndLeavesUnscoredHistoryAlone() {
        runBlocking {
            val scored = sessionDao.insertSession(
                session(startTime = 1_000L, endTime = 2_000L).copy(effortScore = 40)
            )
            val neverScored = sessionDao.insertSession(session(startTime = 3_000L, endTime = 4_000L))

            listOf(scored, neverScored).forEach { id ->
                sessionDao.updateZoneSecondsAndEffort(
                    sessionId = id,
                    zone1 = 1, zone2 = 2, zone3 = 3, zone4 = 4, zone5 = 5,
                    effortScore = 61
                )
            }

            // The scored run follows its new zone times; the unscored one keeps its null, because
            // scoring history is the backfill's job (#62) and a re-tally must not do half of it.
            assertEquals(61, sessionDao.getSessionById(scored)!!.effortScore)
            assertEquals(null, sessionDao.getSessionById(neverScored)!!.effortScore)
            // Both were re-banded either way.
            assertEquals(2L, sessionDao.getSessionById(neverScored)!!.zone2Seconds)
        }
    }

    /**
     * The backfill's work list (#62), which is the whole of what makes that pass resumable: it is
     * re-derived from the rows every time rather than remembered. Both halves of the `WHERE` matter
     * and neither can be shown against a mock.
     */
    @Test
    fun getSessionIdsMissingEffort_isTheFinishedRunsWithNoScore_newestFirst() {
        runBlocking {
            val older = sessionDao.insertSession(session(startTime = 1_000L, endTime = 2_000L))
            val newer = sessionDao.insertSession(session(startTime = 3_000L, endTime = 4_000L))
            sessionDao.insertSession(
                session(startTime = 5_000L, endTime = 6_000L).copy(effortScore = 40)
            )
            // Still recording: the recorder will finalize this row with a Score of its own.
            sessionDao.insertSession(session(startTime = 7_000L, endTime = 0L))

            assertEquals(listOf(newer, older), sessionDao.getSessionIdsMissingEffort())

            // And once a Run has been scored it drops off the list, so running the pass again is a
            // pass over nothing.
            sessionDao.setEffortScore(newer, 12)
            sessionDao.setEffortScore(older, 0)

            assertEquals(emptyList<Long>(), sessionDao.getSessionIdsMissingEffort())
            assertEquals(0, sessionDao.getSessionById(older)!!.effortScore)
        }
    }

    private fun session(startTime: Long, endTime: Long) = RunnerSession(
        startTime = startTime,
        endTime = endTime
    )
}
