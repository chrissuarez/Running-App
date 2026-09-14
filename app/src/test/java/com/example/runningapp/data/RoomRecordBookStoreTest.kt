package com.example.runningapp.data

import com.example.runningapp.SettingsRepository
import com.example.runningapp.UserSettings
import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordType
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * How the app's record book store maps each call onto Room (#487) — one check per call shape.
 *
 * Every rule the book keeps is tested in `RecordBookTest` over a store in memory. What is left for
 * here is the part that store cannot stand in for: which DAO calls a "replace" is made of, the
 * batching a bound-variable limit forces, where the seeding mark is kept, and the gate a track is
 * read through.
 */
class RoomRecordBookStoreTest {

    private val sessionDao: SessionDao = mock()
    private val trackPointDao: TrackPointDao = mock()
    private val achievementDao: AchievementDao = mock()
    private val statedBestEffortDao: StatedBestEffortDao = mock()
    private val runEffortDao: RunEffortDao = mock()
    private val recordFillDao: RecordFillDao = mock()
    private val settingsRepository: SettingsRepository = mock()
    private val transactions = mutableListOf<String>()

    private fun store(settings: SettingsRepository? = settingsRepository) = RoomRecordBookStore(
        sessionDao = sessionDao,
        trackPointDao = trackPointDao,
        achievementDao = achievementDao,
        statedBestEffortDao = statedBestEffortDao,
        runEffortDao = runEffortDao,
        recordFillDao = recordFillDao,
        settingsRepository = settings,
        inTransaction = { block -> transactions += "begin"; block(); transactions += "commit" },
    )

    @Test
    fun `replacing medals takes the records off before putting the new ones on`() = runTest {
        val gold = Achievement(sessionId = 1, type = RecordType.FASTEST_5K, medal = Medal.GOLD, value = 1_300.0)

        store().replaceMedalsOfTypes(listOf(RecordType.FASTEST_5K), listOf(gold))

        inOrder(achievementDao) {
            verify(achievementDao).deleteAchievementsOfTypes(listOf(RecordType.FASTEST_5K))
            verify(achievementDao).insertAchievements(listOf(gold))
        }
    }

    @Test
    fun `replacing one run's claims takes them all off before putting the new ones on`() = runTest {
        val row = RunEffortRow(sessionId = 1, type = RecordType.LONGEST_DURATION, value = 600.0)

        store().replaceEffortsFor(1L, listOf(row))

        inOrder(runEffortDao) {
            verify(runEffortDao).deleteEffortsForSession(1L)
            verify(runEffortDao).putEfforts(listOf(row))
        }
    }

    @Test
    fun `replacing the claims at some records takes those records off before putting the new ones on`() = runTest {
        val row = RunEffortRow(sessionId = 1, type = RecordType.LONGEST_DURATION, value = 600.0)

        store().replaceEffortsOfTypes(listOf(RecordType.LONGEST_DURATION), listOf(row))

        inOrder(runEffortDao) {
            verify(runEffortDao).deleteEffortsOfTypes(listOf(RecordType.LONGEST_DURATION))
            verify(runEffortDao).putEfforts(listOf(row))
        }
    }

    @Test
    fun `marking a history longer than one query can carry marks all of it`() = runTest {
        // Every id is a bound variable, and SQLite takes a bounded number of them.
        store().markScored((1L..1_200L).toList())

        val marked = argumentCaptor<List<Long>>()
        verify(sessionDao, times(3)).setRecordsScoredForSessions(marked.capture())
        assertEquals((1L..1_200L).toList(), marked.allValues.flatten())
        assertTrue(marked.allValues.all { it.size <= MAX_SESSION_IDS_PER_QUERY })
    }

    @Test
    fun `paying the fill writes it down as no longer owed`() = runTest {
        store().markWholesaleFillPaid()

        verify(recordFillDao).put(RecordFillRow(wholesaleFillOwed = false))
    }

    @Test
    fun `the seeding mark is kept in the settings`() = runTest {
        whenever(settingsRepository.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(historyRecordsSeeded = true)))
        val store = store()

        assertEquals(true, store.historySeeded())
        store.clearHistorySeeded()
        store.markHistorySeeded()

        inOrder(settingsRepository) {
            verify(settingsRepository).clearHistoryRecordsSeeded()
            verify(settingsRepository).setHistoryRecordsSeeded()
        }
    }

    @Test
    fun `with no settings there is nowhere to keep the seeding mark`() = runTest {
        assertNull(store(settings = null).historySeeded())
    }

    @Test
    fun `a run's track is read through the map's accuracy gate, breadcrumbs and all`() = runTest {
        // A fix the Run itself refused cannot come back as a record nobody ran; a breadcrumb from
        // before the app kept an accuracy is always kept.
        val breadcrumb = TrackPoint(
            sessionId = 1,
            latitude = 0.0,
            longitude = 0.0,
            timestampMillis = 0L,
            source = TrackPointSource.BACKFILL,
        )
        val refused = TrackPoint(
            sessionId = 1,
            latitude = 0.0,
            longitude = 0.001,
            horizontalAccuracyMeters = 500f,
            timestampMillis = 1_000L,
            source = TrackPointSource.GPS,
        )
        whenever(trackPointDao.getTrackPointsForSessionOnce(1L)).thenReturn(listOf(breadcrumb, refused))

        assertEquals(listOf(breadcrumb), store().track(1L))
    }

    @Test
    fun `the store's transaction is the one it was handed`() = runTest {
        store().inTransaction { transactions += "work" }

        assertEquals(listOf("begin", "work", "commit"), transactions)
    }
}
