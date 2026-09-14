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
 * No settings, no backup, and a transaction that just runs the block. A test whose book needs any
 * of those builds its repository with [repositoryWithRecordBook] instead, which hands the same ones
 * to both (#487).
 */
internal fun recordBookOver(
    sessionDao: SessionDao,
    achievementDao: AchievementDao? = null,
    statedBestEffortDao: StatedBestEffortDao? = null,
    runEffortDao: RunEffortDao? = null,
    recordFillDao: RecordFillDao? = null,
    trackPointDao: TrackPointDao? = null,
): RecordBook = theAppsRecordBook(
    sessionDao, achievementDao, statedBestEffortDao, runEffortDao, recordFillDao, trackPointDao,
    settingsRepository = null,
    refreshHistoryBackup = null,
    inTransaction = { it() },
)

/**
 * A repository handed the app's record book, with the settings, the backup and the transaction given
 * once and reaching both (#487) — as [com.example.runningapp.AppContainer] hands the same ones to
 * each.
 *
 * The book keeps the seeding mark in those settings, refreshes that backup after a mend, and
 * re-reads a Run inside that transaction. Handed different ones from the repository's, a test would
 * be checking a wiring the app never has.
 *
 * The repository's other tables are the ones the tests that need this pass; add one here when a
 * test needs another.
 */
internal fun repositoryWithRecordBook(
    sessionDao: SessionDao,
    achievementDao: AchievementDao? = null,
    statedBestEffortDao: StatedBestEffortDao? = null,
    trackPointDao: TrackPointDao? = null,
    settingsRepository: SettingsRepository? = null,
    refreshHistoryBackup: (suspend () -> Unit)? = null,
    inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    sampleDao: SampleDao? = null,
    intervalStatDao: RunWalkIntervalStatDao? = null,
    runPauseDao: RunPauseDao? = null,
    runShapeDao: RunShapeDao? = null,
    runSummaryDao: RunSummaryDao? = null,
    walkMarkDebtDao: WalkMarkDebtDao? = null,
    aiCoachClient: AiCoachClient? = null,
    bookAfterRunWork: ((Long) -> Unit)? = null,
): SessionRepository = SessionRepository(
    sessionDao = sessionDao,
    sampleDao = sampleDao,
    trackPointDao = trackPointDao,
    intervalStatDao = intervalStatDao,
    runPauseDao = runPauseDao,
    recordBook = theAppsRecordBook(
        sessionDao, achievementDao, statedBestEffortDao, runEffortDao = null, recordFillDao = null,
        trackPointDao, settingsRepository, refreshHistoryBackup, inTransaction,
    ),
    runShapeDao = runShapeDao,
    runSummaryDao = runSummaryDao,
    walkMarkDebtDao = walkMarkDebtDao,
    settingsRepository = settingsRepository,
    aiCoachClient = aiCoachClient,
    refreshHistoryBackup = refreshHistoryBackup,
    bookAfterRunWork = bookAfterRunWork,
    inTransaction = inTransaction,
)

private fun theAppsRecordBook(
    sessionDao: SessionDao,
    achievementDao: AchievementDao?,
    statedBestEffortDao: StatedBestEffortDao?,
    runEffortDao: RunEffortDao?,
    recordFillDao: RecordFillDao?,
    trackPointDao: TrackPointDao?,
    settingsRepository: SettingsRepository?,
    refreshHistoryBackup: (suspend () -> Unit)?,
    inTransaction: suspend (suspend () -> Unit) -> Unit,
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
