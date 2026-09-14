package com.example.runningapp.data

import com.example.runningapp.SettingsRepository
import com.example.runningapp.UserSettings
import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.training.HistoryBestEffort
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
 * here is the part that store cannot stand in for: that every read and write reaches its own table,
 * which DAO calls a "replace" is made of, the batching a bound-variable limit forces, where the
 * seeding mark is kept, and the gate a track is read through.
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
    fun `each single write goes to its own table`() = runTest {
        // The book's in-memory store stands in for these in RecordBookTest, so a write that went
        // nowhere here would pass every rule: a newly scored Run would bank no claims.
        val row = RunEffortRow(sessionId = 1, type = RecordType.LONGEST_DURATION, value = 600.0)
        val stated = StatedBestEffort(sessionId = 1, type = RecordType.FASTEST_5K, seconds = 1_500)
        val store = store()

        store.putEfforts(listOf(row))
        store.markScored(1L)
        store.state(stated)
        store.withdraw(1L, RecordType.FASTEST_5K)

        verify(runEffortDao).putEfforts(listOf(row))
        verify(sessionDao).setRecordsScored(1L)
        verify(statedBestEffortDao).state(stated)
        verify(statedBestEffortDao).withdraw(1L, RecordType.FASTEST_5K)
    }

    @Test
    fun `each read hands back what its own table holds`() = runTest {
        val run = RunnerSession(id = 1L, startTime = 1_700_000_000_000L, runMode = "outdoor")
        val row = RunEffortRow(sessionId = 1, type = RecordType.LONGEST_DURATION, value = 600.0)
        val stated = StatedBestEffort(sessionId = 1, type = RecordType.FASTEST_5K, seconds = 1_500)
        val gold = Achievement(sessionId = 1, type = RecordType.FASTEST_5K, medal = Medal.GOLD, value = 1_300.0)
        whenever(sessionDao.getSessionById(1L)).thenReturn(run)
        whenever(sessionDao.getAllSessions()).thenReturn(listOf(run))
        whenever(sessionDao.getSessionIdsMissingRecordScoring()).thenReturn(listOf(1L))
        whenever(statedBestEffortDao.getForSession(1L)).thenReturn(listOf(stated))
        whenever(statedBestEffortDao.getAll()).thenReturn(listOf(stated))
        whenever(achievementDao.getAllAchievements()).thenReturn(listOf(gold))
        whenever(achievementDao.getAchievementsForSessions(listOf(1L))).thenReturn(listOf(gold))
        whenever(runEffortDao.getEffortsForSession(1L)).thenReturn(listOf(row))
        whenever(runEffortDao.getEffortsOfTypes(listOf(RecordType.LONGEST_DURATION))).thenReturn(listOf(row))
        whenever(recordFillDao.wholesaleFillOwed()).thenReturn(true)
        val store = store()

        assertEquals(run, store.run(1L))
        assertEquals(listOf(run), store.runs())
        assertEquals(listOf(1L), store.runsOwedScoring())
        assertEquals(listOf(stated), store.statedFor(1L))
        assertEquals(listOf(stated), store.allStated())
        assertEquals(listOf(gold), store.medals())
        assertEquals(listOf(gold), store.medalsHeldBy(listOf(1L)))
        assertEquals(listOf(row), store.effortsFor(1L))
        assertEquals(listOf(row), store.effortsOfTypes(listOf(RecordType.LONGEST_DURATION)))
        assertEquals(true, store.wholesaleFillOwed())
    }

    @Test
    fun `each reading the screens watch is its own table's`() {
        val quickest = flowOf<HistoryBestEffort?>(null)
        val counts = flowOf(emptyList<SessionMedalCount>())
        val reading = flowOf(emptyList<RecordsReadingRow>())
        val owed = flowOf(true)
        val stated = flowOf(emptyList<StatedBestEffort>())
        whenever(achievementDao.getQuickestInHistoryFlow(RecordType.FASTEST_5K)).thenReturn(quickest)
        whenever(achievementDao.getMedalCountsFlow()).thenReturn(counts)
        whenever(runEffortDao.getRecordsReadingFlow()).thenReturn(reading)
        whenever(recordFillDao.wholesaleFillOwedFlow()).thenReturn(owed)
        whenever(statedBestEffortDao.getForSessionFlow(1L)).thenReturn(stated)
        val store = store()

        assertSame(quickest, store.quickestInHistoryFlow(RecordType.FASTEST_5K))
        assertSame(counts, store.medalCountsFlow())
        assertSame(reading, store.recordsReadingFlow())
        assertSame(owed, store.wholesaleFillOwedFlow())
        assertSame(stated, store.statedForFlow(1L))
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
