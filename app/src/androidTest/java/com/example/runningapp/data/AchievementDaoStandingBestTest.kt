package com.example.runningapp.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the Stage card reads when it names a bar the runner has already beaten (#293).
 *
 * The query joins the Run in for its date and picks a place out of the book by name, and neither is
 * visible to a mocked DAO: a mock hands back whatever it was told to, including a silver, including
 * a row whose Run was deleted years ago.
 */
@RunWith(AndroidJUnit4::class)
class AchievementDaoStandingBestTest {
    private lateinit var database: AppDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var achievementDao: AchievementDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        sessionDao = database.sessionDao()
        achievementDao = database.achievementDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getStandingBestFlow_answersWithTheGoldAndTheDayItWasRun() {
        runBlocking {
            val fast = sessionDao.insertSession(runStartedAt(1_781_434_800_000L))
            val slower = sessionDao.insertSession(runStartedAt(1_700_000_000_000L))
            achievementDao.insertAchievements(
                listOf(
                    Achievement(sessionId = fast, type = RecordType.FASTEST_5K, medal = Medal.GOLD, value = 1_661.0),
                    Achievement(sessionId = slower, type = RecordType.FASTEST_5K, medal = Medal.SILVER, value = 1_900.0),
                )
            )

            val best = achievementDao.getStandingBestFlow(RecordType.FASTEST_5K, Medal.GOLD).first()

            assertEquals(1_661.0, best?.seconds)
            assertEquals(1_781_434_800_000L, best?.runStartedAtMillis)
        }
    }

    @Test
    fun getStandingBestFlow_saysNothingAboutADistanceNothingHasPlacedAt() {
        runBlocking {
            val run = sessionDao.insertSession(runStartedAt(1_781_434_800_000L))
            achievementDao.insertAchievements(
                listOf(
                    Achievement(sessionId = run, type = RecordType.FASTEST_1K, medal = Medal.GOLD, value = 240.0)
                )
            )

            assertNull(achievementDao.getStandingBestFlow(RecordType.FASTEST_5K, Medal.GOLD).first())
        }
    }

    private fun runStartedAt(startTime: Long) = RunnerSession(
        startTime = startTime,
        endTime = startTime + 1_800_000L,
        durationSeconds = 1_800,
        distanceKm = 5.0
    )
}
