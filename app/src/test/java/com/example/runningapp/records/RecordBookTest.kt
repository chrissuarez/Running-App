package com.example.runningapp.records

import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.data.Achievement
import com.example.runningapp.data.RecordsReadingRow
import com.example.runningapp.data.RunEffortRow
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.SessionMedalCount
import com.example.runningapp.data.StatedBestEffort
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.run.RunMode
import com.example.runningapp.training.HistoryBestEffort
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The record book's rules, over nothing but its own store (#478).
 *
 * The many cases the book has been argued through still live in `SessionRepositoryTest`, where they
 * were written against the DAOs one call at a time. These are the rules a Records bug would break,
 * stated against a store that behaves like a database — so a test can reach one rule without
 * standing up a repository of seventeen tables.
 *
 * Treadmill Runs throughout: the longest distance and the longest time are read off the row, so no
 * test here needs a track to be worth something.
 */
class RecordBookTest {

    /** The record book's tables as the book sees them, in memory. */
    private class Store : RecordBookStore {
        val runs = mutableMapOf<Long, RunnerSession>()
        val stated = mutableListOf<StatedBestEffort>()
        val medals = mutableListOf<Achievement>()
        val efforts = mutableListOf<RunEffortRow>()
        val scored = mutableSetOf<Long>()
        var seeded: Boolean? = false
        var fillOwed = false

        /**
         * The runner acting while a track is being measured — runs once, the first time a track is
         * read. That is the window every compare-and-write in the book exists for.
         */
        var whileMeasuring: (() -> Unit)? = null

        override suspend fun run(sessionId: Long) = runs[sessionId]
        override suspend fun runs() = runs.values.sortedBy { it.id }
        override suspend fun track(sessionId: Long): List<TrackPoint> {
            val interruption = whileMeasuring
            whileMeasuring = null
            interruption?.invoke()
            return emptyList()
        }

        override suspend fun runsOwedScoring() =
            runs.values.filter { it.endTime > 0 && it.id !in scored }.map { it.id }.sorted()

        override suspend fun markScored(sessionId: Long) { scored += sessionId }
        override suspend fun markScored(sessionIds: List<Long>) { scored += sessionIds }

        override suspend fun statedFor(sessionId: Long) = stated.filter { it.sessionId == sessionId }
        override suspend fun allStated() = stated.toList()

        override suspend fun medals() = medals.toList()
        override suspend fun medalsHeldBy(sessionIds: List<Long>) = medals.filter { it.sessionId in sessionIds }
        override suspend fun replaceMedalsOfTypes(types: List<RecordType>, medals: List<Achievement>) {
            this.medals.removeAll { it.type in types }
            this.medals += medals
        }

        override suspend fun effortsFor(sessionId: Long) = efforts.filter { it.sessionId == sessionId }
        override suspend fun replaceEffortsFor(sessionId: Long, efforts: List<RunEffortRow>) {
            this.efforts.removeAll { it.sessionId == sessionId }
            this.efforts += efforts
        }
        override suspend fun putEfforts(efforts: List<RunEffortRow>) {
            efforts.forEach { row -> this.efforts.removeAll { it.sessionId == row.sessionId && it.type == row.type } }
            this.efforts += efforts
        }
        override suspend fun effortsOfTypes(types: List<RecordType>) = efforts.filter { it.type in types }
        override suspend fun replaceEffortsOfTypes(types: List<RecordType>, efforts: List<RunEffortRow>) {
            this.efforts.removeAll { it.type in types }
            this.efforts += efforts
        }

        override suspend fun wholesaleFillOwed() = fillOwed
        override suspend fun markWholesaleFillPaid() { fillOwed = false }

        override suspend fun historySeeded() = seeded
        override suspend fun markHistorySeeded() { seeded = true }
        override suspend fun clearHistorySeeded() { seeded = false }

        override suspend fun inTransaction(block: suspend () -> Unit) = block()

        // The screens' reads, as one reading of the tables as they stand.
        override fun quickestInHistoryFlow(type: RecordType) = flowOf(
            medals.filter { it.type == type }.minByOrNull { it.value }
                ?.let { HistoryBestEffort(seconds = it.value, runStartedAtMillis = 0L) }
        )
        override fun medalCountsFlow() =
            flowOf(medals.groupBy { it.sessionId }.map { (id, held) -> SessionMedalCount(id, held.size) })
        override fun recordsReadingFlow() = flowOf(emptyList<RecordsReadingRow>())
        override fun wholesaleFillOwedFlow() = flowOf(fillOwed)
        override fun statedForFlow(sessionId: Long) = flowOf(stated.filter { it.sessionId == sessionId })
        override suspend fun state(effort: StatedBestEffort) {
            withdraw(effort.sessionId, effort.type)
            stated += effort
        }
        override suspend fun withdraw(sessionId: Long, type: RecordType) {
            stated.removeAll { it.sessionId == sessionId && it.type == type }
        }

        /** A Run leaving history, with what a real delete cascades away going with it. */
        fun delete(sessionId: Long) {
            runs.remove(sessionId)
            medals.removeAll { it.sessionId == sessionId }
            efforts.removeAll { it.sessionId == sessionId }
            stated.removeAll { it.sessionId == sessionId }
        }
    }

    private val store = Store()
    private var backups = 0
    private val book = RecordBook(store, refreshHistoryBackup = { backups++ })

    private fun aTreadmillRun(id: Long, km: Double, seconds: Long, finished: Boolean = true) {
        store.runs[id] = RunnerSession(
            id = id,
            startTime = 1_000_000L * id,
            endTime = if (finished) 1_000_000L * id + seconds * 1_000 else 0L,
            runMode = RunMode.TREADMILL.settingValue,
            distanceKm = km,
            durationSeconds = seconds,
        )
    }

    private fun holders(type: RecordType): List<Pair<Long, Medal>> =
        store.medals.filter { it.type == type }.sortedBy { it.medal.ordinal }.map { it.sessionId to it.medal }

    @Test
    fun `scoring a run ranks it against the book and banks what it was worth`() = runTest {
        aTreadmillRun(1, km = 10.0, seconds = 3_000)
        aTreadmillRun(2, km = 12.0, seconds = 2_000)

        book.scoreAndMark(1)
        val earned = book.scoreAndMark(2)

        assertEquals(listOf(2L to Medal.GOLD, 1L to Medal.SILVER), holders(RecordType.LONGEST_DISTANCE))
        assertEquals(listOf(1L to Medal.GOLD, 2L to Medal.SILVER), holders(RecordType.LONGEST_DURATION))
        assertEquals(
            setOf(RecordType.LONGEST_DISTANCE to Medal.GOLD, RecordType.LONGEST_DURATION to Medal.SILVER),
            earned.map { it.type to it.medal }.toSet(),
        )
        assertEquals(
            setOf(RunEffortRow(2, RecordType.LONGEST_DISTANCE, 12_000.0), RunEffortRow(2, RecordType.LONGEST_DURATION, 2_000.0)),
            store.effortsFor(2).toSet(),
        )
        assertEquals(setOf(1L, 2L), store.scored)
    }

    @Test
    fun `a run that changes while it is measured is left unscored and still owing`() = runTest {
        aTreadmillRun(1, km = 10.0, seconds = 3_000)
        store.whileMeasuring = { store.runs[1] = store.runs.getValue(1).copy(distanceKm = 5.0) }

        val earned = book.scoreAndMark(1)

        assertTrue(earned.isEmpty())
        assertTrue(store.medals.isEmpty())
        assertTrue(store.efforts.isEmpty())
        assertFalse(1L in store.scored)
    }

    @Test
    fun `seeding builds the whole book at once and settles every debt it paid`() = runTest {
        aTreadmillRun(1, km = 10.0, seconds = 3_000)
        aTreadmillRun(2, km = 12.0, seconds = 2_000)
        aTreadmillRun(3, km = 20.0, seconds = 9_000, finished = false)
        store.fillOwed = true

        book.seedFromHistory()

        // A Run still being recorded is worth nothing yet, so it places nowhere.
        assertEquals(listOf(2L to Medal.GOLD, 1L to Medal.SILVER), holders(RecordType.LONGEST_DISTANCE))
        assertEquals(true, store.seeded)
        assertEquals(setOf(1L, 2L), store.scored)
        assertFalse(store.fillOwed)
    }

    @Test
    fun `the launch pass scores nothing while history still owes its seeding`() = runTest {
        aTreadmillRun(1, km = 10.0, seconds = 3_000)

        book.scoreMissed()

        assertTrue(store.medals.isEmpty())
        assertTrue(store.scored.isEmpty())
    }

    @Test
    fun `the launch pass scores what the book missed once history is seeded`() = runTest {
        aTreadmillRun(1, km = 10.0, seconds = 3_000)
        store.seeded = true
        store.fillOwed = true

        book.scoreMissed()

        assertEquals(listOf(1L to Medal.GOLD), holders(RecordType.LONGEST_DISTANCE))
        assertEquals(setOf(1L), store.scored)
        assertFalse(store.fillOwed)
    }

    @Test
    fun `deleting a medal holder moves the runs below it up and hands the seeding mark back`() = runTest {
        aTreadmillRun(1, km = 10.0, seconds = 3_000)
        aTreadmillRun(2, km = 12.0, seconds = 2_000)
        aTreadmillRun(3, km = 8.0, seconds = 1_000)
        book.seedFromHistory()

        book.changeAndRepair(listOf(2L)) { store.delete(2L) }

        assertEquals(listOf(1L to Medal.GOLD, 3L to Medal.SILVER), holders(RecordType.LONGEST_DISTANCE))
        assertEquals(true, store.seeded)
        // Once as the rows go, and once more for the mended book.
        assertEquals(2, backups)
    }

    @Test
    fun `a change that does not land leaves history owing a reseed`() = runTest {
        aTreadmillRun(1, km = 10.0, seconds = 3_000)
        book.seedFromHistory()

        try {
            book.changeAndRepair(listOf(1L)) { error("disk full") }
            fail("the change should have thrown")
        } catch (e: IllegalStateException) {
            // expected
        }

        assertEquals(false, store.seeded)
    }

    @Test
    fun `a stated best effort is what a treadmill run is worth at that distance`() = runTest {
        aTreadmillRun(1, km = 6.0, seconds = 2_000)
        store.stated += StatedBestEffort(sessionId = 1, type = RecordType.FASTEST_5K, seconds = 1_500)

        val worth = book.worthAt(store.runs.getValue(1), listOf(RecordType.FASTEST_5K))

        assertEquals(listOf(RecordType.FASTEST_5K to 1_500.0), worth.map { it.type to it.value })
    }

    @Test
    fun `a change overtaken while it is re-measured keeps its claims and leaves history owing`() = runTest {
        aTreadmillRun(1, km = 10.0, seconds = 3_000)
        aTreadmillRun(2, km = 12.0, seconds = 4_000)
        aTreadmillRun(3, km = 14.0, seconds = 5_000)
        // Fourth at both Records, so it holds no medal and no rebuild comes behind its change.
        aTreadmillRun(4, km = 5.0, seconds = 1_000)
        book.seedFromHistory()

        book.changeAndRepair(listOf(4L)) {
            store.runs[4] = store.runs.getValue(4).copy(distanceKm = 6.0)
            // A second edit, landing while the first one's re-banking is measuring the Run.
            store.whileMeasuring = { store.runs[4] = store.runs.getValue(4).copy(distanceKm = 7.0) }
        }

        // The re-banking stood down rather than write a reading the Run no longer agrees with.
        assertEquals(
            listOf(RunEffortRow(4, RecordType.LONGEST_DISTANCE, 5_000.0)),
            store.effortsFor(4).filter { it.type == RecordType.LONGEST_DISTANCE },
        )
        assertEquals(false, store.seeded)
    }

    @Test
    fun `seeding stands down when a stated best effort changes while history is measured`() = runTest {
        aTreadmillRun(1, km = 6.0, seconds = 2_000)
        store.whileMeasuring = {
            store.stated += StatedBestEffort(sessionId = 1, type = RecordType.FASTEST_5K, seconds = 1_500)
        }

        book.seedFromHistory()

        assertTrue(store.medals.isEmpty())
        assertEquals(false, store.seeded)
        assertTrue(store.scored.isEmpty())
    }
}
