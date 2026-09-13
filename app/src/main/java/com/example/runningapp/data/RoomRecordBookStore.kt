package com.example.runningapp.data

import com.example.runningapp.SettingsRepository
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.records.RecordBook
import com.example.runningapp.records.RecordBookStore
import kotlinx.coroutines.flow.first

/**
 * The record book's tables in Room (#484) — the store [RecordBook] reads and writes in the app.
 *
 * Plain pass-throughs to the DAOs, one call each, and nothing decided here: every rule is the
 * book's. Built once, in [com.example.runningapp.AppContainer], and handed to the one book there is.
 *
 * [settingsRepository] is where the whole-history seeding mark is kept. Null leaves the book with
 * nowhere to keep it, which the passes that would pay it read as nothing to do — see
 * [RecordBookStore.historySeeded].
 *
 * [inTransaction] is the database's own transaction, the same one the repository writes Runs in:
 * the book's compare-and-writes only hold if a Run's change and the book's re-read queue behind the
 * one writer.
 */
class RoomRecordBookStore(
    private val sessionDao: SessionDao,
    private val trackPointDao: TrackPointDao,
    private val achievementDao: AchievementDao,
    private val statedBestEffortDao: StatedBestEffortDao,
    private val runEffortDao: RunEffortDao,
    private val recordFillDao: RecordFillDao,
    private val settingsRepository: SettingsRepository?,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
) : RecordBookStore {

    override suspend fun run(sessionId: Long) = sessionDao.getSessionById(sessionId)
    override suspend fun runs() = sessionDao.getAllSessions()

    // The same accuracy-gated fixes the map, the splits and the GPX export are built from.
    override suspend fun track(sessionId: Long) =
        trackPointDao.getTrackPointsForSessionOnce(sessionId).acceptedForMap()

    override suspend fun runsOwedScoring() = sessionDao.getSessionIdsMissingRecordScoring()
    override suspend fun markScored(sessionId: Long) = sessionDao.setRecordsScored(sessionId)

    // In batches, because every id is a bound variable and SQLite takes a bounded number of them: a
    // history long enough to be worth seeding is a history long enough to exceed it.
    override suspend fun markScored(sessionIds: List<Long>) =
        sessionIds.chunked(MAX_SESSION_IDS_PER_QUERY)
            .forEach { sessionDao.setRecordsScoredForSessions(it) }

    override suspend fun statedFor(sessionId: Long) = statedBestEffortDao.getForSession(sessionId)
    override suspend fun allStated() = statedBestEffortDao.getAll()

    override suspend fun medals() = achievementDao.getAllAchievements()
    override suspend fun medalsHeldBy(sessionIds: List<Long>) =
        achievementDao.getAchievementsForSessions(sessionIds)
    override suspend fun replaceMedalsOfTypes(types: List<RecordType>, medals: List<Achievement>) {
        achievementDao.deleteAchievementsOfTypes(types)
        achievementDao.insertAchievements(medals)
    }

    override suspend fun effortsFor(sessionId: Long) = runEffortDao.getEffortsForSession(sessionId)
    override suspend fun replaceEffortsFor(sessionId: Long, efforts: List<RunEffortRow>) {
        runEffortDao.deleteEffortsForSession(sessionId)
        runEffortDao.putEfforts(efforts)
    }
    override suspend fun putEfforts(efforts: List<RunEffortRow>) = runEffortDao.putEfforts(efforts)
    override suspend fun effortsOfTypes(types: List<RecordType>) = runEffortDao.getEffortsOfTypes(types)
    override suspend fun replaceEffortsOfTypes(types: List<RecordType>, efforts: List<RunEffortRow>) {
        runEffortDao.deleteEffortsOfTypes(types)
        runEffortDao.putEfforts(efforts)
    }

    override suspend fun wholesaleFillOwed() = recordFillDao.wholesaleFillOwed()
    override suspend fun markWholesaleFillPaid() =
        recordFillDao.put(RecordFillRow(wholesaleFillOwed = false))

    override suspend fun historySeeded() =
        settingsRepository?.userSettingsFlow?.first()?.historyRecordsSeeded
    override suspend fun markHistorySeeded() {
        settingsRepository?.setHistoryRecordsSeeded()
    }
    override suspend fun clearHistorySeeded() {
        settingsRepository?.clearHistoryRecordsSeeded()
    }

    override suspend fun inTransaction(block: suspend () -> Unit) = inTransaction.invoke(block)

    override fun quickestInHistoryFlow(type: RecordType) = achievementDao.getQuickestInHistoryFlow(type)
    override fun medalCountsFlow() = achievementDao.getMedalCountsFlow()
    override fun recordsReadingFlow() = runEffortDao.getRecordsReadingFlow()
    override fun wholesaleFillOwedFlow() = recordFillDao.wholesaleFillOwedFlow()
    override fun statedForFlow(sessionId: Long) = statedBestEffortDao.getForSessionFlow(sessionId)
    override suspend fun state(effort: StatedBestEffort) = statedBestEffortDao.state(effort)
    override suspend fun withdraw(sessionId: Long, type: RecordType) =
        statedBestEffortDao.withdraw(sessionId, type)
}
