package com.example.runningapp.data

import com.example.runningapp.SettingsRepository
import com.example.runningapp.records.RecordBook
import kotlinx.coroutines.flow.flowOf
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * The app's record book — [RecordBook] over [RoomRecordBookStore] — over whichever of its tables a
 * test cares about (#484).
 *
 * A table the test passes is the test's own mock, and every call the book makes is made on it
 * exactly as the app makes it on Room. A table the test leaves out is an empty one: it answers every
 * read with nothing and takes every write. That is the whole of the store standing, as the app's
 * is — the book has no way to be told a table is missing.
 *
 * The repository's own [SessionDao], settings, backup and transaction are passed in again, because
 * the book reaches them on its own and has to reach the same ones.
 */
internal fun recordBookOver(
    sessionDao: SessionDao,
    achievementDao: AchievementDao? = null,
    statedBestEffortDao: StatedBestEffortDao? = null,
    runEffortDao: RunEffortDao? = null,
    recordFillDao: RecordFillDao? = null,
    trackPointDao: TrackPointDao? = null,
    settingsRepository: SettingsRepository? = null,
    refreshHistoryBackup: (suspend () -> Unit)? = null,
    inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
): RecordBook = RecordBook(
    RoomRecordBookStore(
        sessionDao = sessionDao,
        trackPointDao = trackPointDao ?: mock {
            onBlocking { getTrackPointsForSessionOnce(any()) } doReturn emptyList()
        },
        achievementDao = achievementDao ?: mock {
            onBlocking { getAllAchievements() } doReturn emptyList()
            onBlocking { getAchievementsForSessions(any()) } doReturn emptyList()
            on { getMedalCountsFlow() } doReturn flowOf(emptyList())
            on { getQuickestInHistoryFlow(any()) } doReturn flowOf(null)
        },
        statedBestEffortDao = statedBestEffortDao ?: mock {
            onBlocking { getForSession(any()) } doReturn emptyList()
            onBlocking { getAll() } doReturn emptyList()
            on { getForSessionFlow(any()) } doReturn flowOf(emptyList())
        },
        runEffortDao = runEffortDao ?: mock {
            onBlocking { getEffortsForSession(any()) } doReturn emptyList()
            onBlocking { getEffortsOfTypes(any()) } doReturn emptyList()
            on { getRecordsReadingFlow() } doReturn flowOf(emptyList())
        },
        recordFillDao = recordFillDao ?: mock {
            onBlocking { wholesaleFillOwed() } doReturn false
            on { wholesaleFillOwedFlow() } doReturn flowOf(false)
        },
        settingsRepository = settingsRepository,
        inTransaction = inTransaction,
    ),
    refreshHistoryBackup = refreshHistoryBackup,
)
