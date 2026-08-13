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
 * The ceiling the coach's prescription is held under, and what may raise it (#275).
 *
 * Like the Stage evidence query, the rule is entirely in the `WHERE`: a mocked DAO hands back
 * whatever the test tells it to, so only the real query can show that a Walk does not lift a
 * running runner's limit.
 */
@RunWith(AndroidJUnit4::class)
class SessionDaoRecentLoadTest {
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
    fun getMaxSessionLoadLast30Days_doesNotCountAWalkAsRunningLoad() {
        runBlocking {
            // Twenty minutes run, two hours walked. The ceiling is the run.
            sessionDao.insertSession(
                aSession(startTime = 1_000L, durationSeconds = 1_200L, distanceKm = 4.0)
            )
            sessionDao.insertSession(
                aSession(startTime = 2_000L, durationSeconds = 7_200L, distanceKm = 10.0)
                    .copy(isWalk = true)
            )

            val load = sessionDao.getMaxSessionLoadLast30Days(cutoffEpochMillis = 0L)

            assertEquals(1_200L, load.maxDurationSeconds)
            assertEquals(4.0, load.maxDistanceKm!!, 0.0)
        }
    }

    @Test
    fun getMaxSessionLoadLast30Days_keepsTheOtherTwoConditions() {
        runBlocking {
            val counted = aSession(startTime = 50_000L, durationSeconds = 1_200L, distanceKm = 4.0)
            sessionDao.insertSession(counted)
            // Still recording, and finished before the cutoff — each excluded before this ticket
            // and still excluded now.
            sessionDao.insertSession(
                aSession(startTime = 60_000L, durationSeconds = 9_000L, distanceKm = 20.0)
                    .copy(endTime = 0L)
            )
            sessionDao.insertSession(
                aSession(startTime = 100L, durationSeconds = 9_000L, distanceKm = 20.0)
            )

            val load = sessionDao.getMaxSessionLoadLast30Days(cutoffEpochMillis = 10_000L)

            assertEquals(1_200L, load.maxDurationSeconds)
            assertEquals(4.0, load.maxDistanceKm!!, 0.0)
        }
    }

    @Test
    fun getMaxSessionLoadLast30Days_isEmptyWhenEveryRecentSessionIsAWalk() {
        runBlocking {
            sessionDao.insertSession(
                aSession(startTime = 1_000L, durationSeconds = 7_200L, distanceKm = 10.0)
                    .copy(isWalk = true)
            )

            // Null rather than zero, which is how the repository tells "no running to measure
            // against" from "a runner who ran nothing" — the clamp then leaves the coach alone.
            val load = sessionDao.getMaxSessionLoadLast30Days(cutoffEpochMillis = 0L)

            assertEquals(null, load.maxDurationSeconds)
            assertEquals(null, load.maxDistanceKm)
        }
    }

    private fun aSession(startTime: Long, durationSeconds: Long, distanceKm: Double) = RunnerSession(
        startTime = startTime,
        endTime = startTime + 1_000L,
        durationSeconds = durationSeconds,
        distanceKm = distanceKm,
        includeInAiTraining = true,
    )
}
