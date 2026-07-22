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

    private fun session(startTime: Long, endTime: Long) = RunnerSession(
        startTime = startTime,
        endTime = endTime
    )
}
