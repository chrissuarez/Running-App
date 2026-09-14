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
import com.example.runningapp.data.TrackPointSource
import com.example.runningapp.run.RunMode
import com.example.runningapp.training.HistoryBestEffort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The record book's rules, over nothing but its own store (#478, #487).
 *
 * Every Record rule is argued here, stated against a store that behaves like a database, and
 * checked by what the store holds afterwards rather than by which calls reached it — so a change to
 * how the app's store talks to Room cannot break a test about a rule. What the repository's doors
 * hand the book is tested in `SessionRepositoryTest`; how the app's store maps each call onto a DAO
 * is tested in `RoomRecordBookStoreTest`.
 *
 * Treadmill Runs throughout, except where a track is the point: the longest distance and the
 * longest time are read off the row, so those tests need no track to be worth something.
 */
class RecordBookTest {

    /** The record book's tables as the book sees them, in memory. */
    private class Store : RecordBookStore {
        val runs = mutableMapOf<Long, RunnerSession>()
        val tracks = mutableMapOf<Long, List<TrackPoint>>()
        val stated = mutableListOf<StatedBestEffort>()
        val medals = mutableListOf<Achievement>()
        val efforts = mutableListOf<RunEffortRow>()
        val scored = mutableSetOf<Long>()
        var seeded: Boolean? = false
        var fillOwed = false

        /** What happened, in order: the moments the book's ordering rules are about. */
        val events = mutableListOf<String>()

        /**
         * The runner acting while a track is being measured — runs once, the first time a track is
         * read. That is the window every compare-and-write in the book exists for.
         */
        var whileMeasuring: (suspend () -> Unit)? = null

        /** Held before the next transaction opens, and only that one. */
        var beforeNextTransaction: (suspend () -> Unit)? = null

        /** Runs whose rows cannot be read. */
        val unreadable = mutableSetOf<Long>()
        var historyUnreadable = false
        var historyReads = 0
        var medalWritesFail = false
        var clearingTheSeededMarkFailsOnce = false
        var fillWrites = 0

        override suspend fun run(sessionId: Long): RunnerSession? {
            if (sessionId in unreadable) throw IllegalStateException("unreadable row")
            return runs[sessionId]
        }

        override suspend fun runs(): List<RunnerSession> {
            if (historyUnreadable) throw IllegalStateException("history unreadable")
            historyReads++
            return runs.values.sortedBy { it.id }
        }

        override suspend fun track(sessionId: Long): List<TrackPoint> {
            val interruption = whileMeasuring
            whileMeasuring = null
            interruption?.invoke()
            return tracks[sessionId].orEmpty()
        }

        override suspend fun runsOwedScoring() =
            runs.values.filter { it.endTime > 0 && it.id !in scored }.map { it.id }.sorted()

        override suspend fun markScored(sessionId: Long) {
            scored += sessionId
            events += "scored $sessionId"
        }
        override suspend fun markScored(sessionIds: List<Long>) { scored += sessionIds }

        override suspend fun statedFor(sessionId: Long) = stated.filter { it.sessionId == sessionId }
        override suspend fun allStated() = stated.toList()

        override suspend fun medals() = medals.toList()
        override suspend fun medalsHeldBy(sessionIds: List<Long>): List<Achievement> {
            events += "read medals"
            return medals.filter { it.sessionId in sessionIds }
        }
        override suspend fun replaceMedalsOfTypes(types: List<RecordType>, medals: List<Achievement>) {
            if (medalWritesFail) throw IllegalStateException("disk full")
            this.medals.removeAll { it.type in types }
            this.medals += medals
            events += "medals written"
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
        override suspend fun markWholesaleFillPaid() {
            fillOwed = false
            fillWrites++
            events += "fill paid"
        }

        override suspend fun historySeeded() = seeded
        override suspend fun markHistorySeeded() {
            seeded = true
            events += "seeded"
        }
        override suspend fun clearHistorySeeded() {
            if (clearingTheSeededMarkFailsOnce) {
                clearingTheSeededMarkFailsOnce = false
                throw IllegalStateException("the settings store is unwell")
            }
            seeded = false
            events += "seeding owed"
        }

        override suspend fun inTransaction(block: suspend () -> Unit) {
            val hold = beforeNextTransaction
            beforeNextTransaction = null
            hold?.invoke()
            events += "begin"
            block()
            events += "commit"
        }

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
            events += "delete"
        }
    }

    private val store = Store()
    private var backups = 0
    private val book = RecordBook(store, refreshHistoryBackup = { backups++; store.events += "backup" })

    private fun Store.aTreadmillRun(
        id: Long,
        km: Double = 0.0,
        seconds: Long,
        finished: Boolean = true,
        isWalk: Boolean = false,
    ) {
        runs[id] = RunnerSession(
            id = id,
            startTime = 1_000_000L * id,
            endTime = if (finished) 1_000_000L * id + seconds * 1_000 else 0L,
            runMode = RunMode.TREADMILL.settingValue,
            distanceKm = km,
            durationSeconds = seconds,
            isWalk = isWalk,
        )
    }

    private fun aTreadmillRun(id: Long, km: Double = 0.0, seconds: Long, finished: Boolean = true, isWalk: Boolean = false) =
        store.aTreadmillRun(id, km, seconds, finished, isWalk)

    private fun Store.holders(type: RecordType): List<Pair<Long, Medal>> =
        medals.filter { it.type == type }.sortedBy { it.medal.ordinal }.map { it.sessionId to it.medal }

    private fun holders(type: RecordType) = store.holders(type)

    private fun stated(sessionId: Long, type: RecordType, seconds: Int) =
        StatedBestEffort(sessionId = sessionId, type = type, seconds = seconds)

    // --- Scoring one Run (#49, #75, #210) ---

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
    fun `scoring a run rewrites only the records it contested`() = runTest {
        // An outdoor Run's 5K standing on the book: a treadmill Run contests none of the fastest
        // five, so it must not be able to clear them off on its way past.
        store.medals += Achievement(sessionId = 5, type = RecordType.FASTEST_5K, medal = Medal.GOLD, value = 1_300.0)
        aTreadmillRun(7, seconds = 3_600)

        val earned = book.score(7)

        assertEquals(listOf(RecordType.LONGEST_DURATION to Medal.GOLD), earned.map { it.type to it.medal })
        assertEquals(listOf(5L to Medal.GOLD), holders(RecordType.FASTEST_5K))
    }

    @Test
    fun `a run still being recorded is not scored at all`() = runTest {
        aTreadmillRun(7, km = 5.0, seconds = 1_800, finished = false)

        assertTrue(book.score(7).isEmpty())

        assertTrue(store.medals.isEmpty())
        assertTrue(store.efforts.isEmpty())
    }

    @Test
    fun `a run that never placed is still banked, so the top ten can go deeper than the book`() = runTest {
        // A book already three deep at both records, every place held by somebody longer.
        aTreadmillRun(1, km = 20.0, seconds = 7_200)
        aTreadmillRun(2, km = 19.0, seconds = 7_100)
        aTreadmillRun(3, km = 18.0, seconds = 7_000)
        book.seedFromHistory()
        aTreadmillRun(9, km = 0.5, seconds = 60)

        book.scoreAndMark(9)

        // Nothing of this Run reached the book — and all of it reached the rows the Records section
        // reads, which is the whole point of them.
        assertTrue(store.medals.none { it.sessionId == 9L })
        assertEquals(
            setOf(RunEffortRow(9, RecordType.LONGEST_DISTANCE, 500.0), RunEffortRow(9, RecordType.LONGEST_DURATION, 60.0)),
            store.effortsFor(9).toSet(),
        )
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
    fun `a run deleted while it is being measured is not written to the book`() = runTest {
        aTreadmillRun(7, seconds = 1_800)
        store.whileMeasuring = { store.delete(7) }

        book.scoreAndMark(7)

        // A medal for a Run that no longer exists, standing over the record it took.
        assertTrue(store.medals.isEmpty())
        assertFalse(7L in store.scored)
    }

    @Test
    fun `a run whose Effort Score lands mid-measure is scored anyway`() = runTest {
        // The Effort backfill runs at the same launch and writes to every Run in history. It cannot
        // move a distance or a duration, so it is not a reason to abandon a scoring.
        aTreadmillRun(7, seconds = 1_800)
        store.whileMeasuring = {
            store.runs[7] = store.runs.getValue(7).copy(effortScore = 42, sessionNote = "hard")
        }

        book.scoreAndMark(7)

        assertEquals(listOf(7L to Medal.GOLD), holders(RecordType.LONGEST_DURATION))
        assertTrue(7L in store.scored)
    }

    @Test
    fun `a stated best effort is what a treadmill run is worth at that distance`() = runTest {
        aTreadmillRun(1, km = 6.0, seconds = 2_000)
        store.stated += stated(1, RecordType.FASTEST_5K, 1_500)

        val worth = book.worthAt(store.runs.getValue(1), listOf(RecordType.FASTEST_5K))

        assertEquals(listOf(RecordType.FASTEST_5K to 1_500.0), worth.map { it.type to it.value })
    }

    // --- The launch pass: the Runs whose scoring was missed (#210), and the fill (#75) ---

    @Test
    fun `the launch pass scores nothing while history still owes its seeding`() = runTest {
        aTreadmillRun(1, km = 10.0, seconds = 3_000)
        store.fillOwed = true

        book.scoreMissed()

        // The seeding pass is about to measure all of it anyway, and its book is the better one —
        // so this pass is in no position to call the table whole either.
        assertTrue(store.medals.isEmpty())
        assertTrue(store.scored.isEmpty())
        assertTrue(store.fillOwed)
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
    fun `a run whose scoring cannot be written stays owed rather than being marked`() = runTest {
        aTreadmillRun(7, seconds = 1_800)
        store.seeded = true
        store.medalWritesFail = true

        // Does not throw: the launch scope has no handler behind it.
        book.scoreMissed()

        assertTrue(store.scored.isEmpty())
    }

    @Test
    fun `one run the pass cannot score costs the next one nothing`() = runTest {
        aTreadmillRun(7, seconds = 1_800)
        aTreadmillRun(8, seconds = 600)
        store.unreadable += 7L
        store.seeded = true

        book.scoreMissed()

        assertEquals(setOf(8L), store.scored)
    }

    @Test
    fun `running the launch pass twice leaves the same book, with no run racing itself`() = runTest {
        // The mark is written after the scoring, so a process that dies in between costs a Run one
        // redundant re-score. This is what that re-score has to be worth: nothing at all.
        aTreadmillRun(7, seconds = 1_800)
        store.seeded = true

        book.scoreMissed()
        val afterOnce = store.medals.map { Triple(it.sessionId, it.medal, it.type) }
        store.scored.clear()
        book.scoreMissed()

        assertEquals(listOf(Triple(7L, Medal.GOLD, RecordType.LONGEST_DURATION)), afterOnce)
        assertEquals(afterOnce, store.medals.map { Triple(it.sessionId, it.medal, it.type) })
    }

    @Test
    fun `scoring runs one at a time reaches the same book as a rebuild over the same history`() = runTest {
        fun Store.history() {
            aTreadmillRun(1, seconds = 600)
            aTreadmillRun(2, seconds = 3_600)
            aTreadmillRun(3, seconds = 1_200)
            aTreadmillRun(4, seconds = 2_400)
            aTreadmillRun(5, seconds = 900)
        }
        val oneAtATime = Store().apply { history(); seeded = true }
        val allAtOnce = Store().apply { history() }

        RecordBook(oneAtATime).scoreMissed()
        RecordBook(allAtOnce).seedFromHistory()

        assertEquals(allAtOnce.holders(RecordType.LONGEST_DURATION), oneAtATime.holders(RecordType.LONGEST_DURATION))
        assertEquals(
            listOf(2L to Medal.GOLD, 4L to Medal.SILVER, 3L to Medal.BRONZE),
            oneAtATime.holders(RecordType.LONGEST_DURATION),
        )
    }

    @Test
    fun `scoring a run as it finishes never touches the fill`() = runTest {
        // The fill is a statement about the whole table, and one Run being measured says nothing
        // about the whole table.
        aTreadmillRun(7, seconds = 1_800)
        store.fillOwed = true

        book.scoreAndMark(7)

        assertTrue(store.fillOwed)
        assertEquals(0, store.fillWrites)
    }

    @Test
    fun `the launch pass hands the fill back only once it has been through every owed run`() = runTest {
        // The order is the whole guarantee: anything that cuts the pass short has to leave the fill
        // standing for the next launch to finish, and it does exactly when the hand-back is last.
        aTreadmillRun(7, seconds = 1_800)
        aTreadmillRun(8, seconds = 600)
        store.seeded = true
        store.fillOwed = true

        book.scoreMissed()

        assertEquals(
            listOf("scored 7", "scored 8", "fill paid"),
            store.events.filter { it.startsWith("scored") || it == "fill paid" },
        )
    }

    @Test
    fun `a run the launch pass cannot measure does not hide the records for ever`() = runTest {
        // The Run keeps its own debt and is tried again at every launch. But the fill is a statement
        // about the table, and holding it up behind one Run that may never measure would leave the
        // runner reading "still measuring your runs" until they deleted it.
        aTreadmillRun(7, seconds = 1_800)
        store.unreadable += 7L
        store.seeded = true
        store.fillOwed = true

        book.scoreMissed()

        assertFalse(7L in store.scored)
        assertFalse(store.fillOwed)
    }

    @Test
    fun `a launch pass with nothing owing still hands back a fill that was standing`() = runTest {
        // A pass cut short after its last Run was marked and before the hand-back leaves the next
        // launch finding nothing owing at all, and it is the one that has to close the fill.
        store.seeded = true
        store.fillOwed = true

        book.scoreMissed()

        assertFalse(store.fillOwed)
    }

    @Test
    fun `a launch that was owed no fill writes nothing at all`() = runTest {
        // Every launch runs this pass. Writing the row anyway would wake the Records section on
        // every one of them, for ever, to tell it what it already knew.
        store.seeded = true

        book.scoreMissed()

        assertEquals(0, store.fillWrites)
    }

    // --- Seeding the book from the whole of history (#50) ---

    @Test
    fun `seeding builds the whole book at once and settles every debt it paid`() = runTest {
        aTreadmillRun(1, km = 10.0, seconds = 3_000)
        aTreadmillRun(2, km = 12.0, seconds = 2_000)
        aTreadmillRun(3, km = 20.0, seconds = 9_000, finished = false)
        store.fillOwed = true

        book.seedFromHistory()

        // A Run still being recorded is worth nothing yet, so it places nowhere, and it is not
        // marked: it will score itself when it finishes.
        assertEquals(listOf(2L to Medal.GOLD, 1L to Medal.SILVER), holders(RecordType.LONGEST_DISTANCE))
        assertEquals(true, store.seeded)
        assertEquals(setOf(1L, 2L), store.scored)
        assertFalse(store.fillOwed)
    }

    @Test
    fun `history already seeded is not measured again`() = runTest {
        aTreadmillRun(1, seconds = 600)
        store.seeded = true

        book.seedFromHistory()

        assertEquals(0, store.historyReads)
        assertTrue(store.medals.isEmpty())
    }

    @Test
    fun `a seeding pass that cannot write the book leaves every debt standing`() = runTest {
        aTreadmillRun(1, seconds = 600)
        store.fillOwed = true
        store.medalWritesFail = true

        // Does not throw: the launch scope has no handler behind it.
        book.seedFromHistory()

        // The pass is owed again, and so is every Run in it and the fill: a mark here would be a
        // debt cancelled by a book that was never written.
        assertEquals(false, store.seeded)
        assertTrue(store.scored.isEmpty())
        assertTrue(store.fillOwed)
    }

    @Test
    fun `seeding measures an outdoor run's track, breadcrumbs and all`() = runTest {
        // Sparse breadcrumbs — which is what history from before the app kept an accuracy looks
        // like. They still covered ground, so the Run contests the distances.
        store.runs[1] = RunnerSession(
            id = 1,
            startTime = 0L,
            endTime = 300_000L,
            runMode = RunMode.OUTDOOR.settingValue,
            distanceKm = 1.2,
            durationSeconds = 300,
        )
        // 4 m/s north from the equator: 1.2 km in five minutes, fixes ten seconds apart.
        store.tracks[1] = (0..300 step 10).map { second ->
            TrackPoint(
                sessionId = 1,
                latitude = second * 4.0 / 111_320.0,
                longitude = 0.0,
                timestampMillis = second * 1_000L,
                source = TrackPointSource.BACKFILL,
            )
        }

        book.seedFromHistory()

        assertEquals(
            setOf(RecordType.FASTEST_1K, RecordType.LONGEST_DISTANCE, RecordType.LONGEST_DURATION),
            store.medals.map { it.type }.toSet(),
        )
        assertEquals(250.0, store.medals.single { it.type == RecordType.FASTEST_1K }.value, 15.0)
    }

    @Test
    fun `a run scored while history was being measured keeps its place in the book`() = runTest {
        // Run 9 was still being recorded when the pass read history, so it measures to nothing —
        // then finished and scored itself. Its rows would be wiped by the rewrite if the book did
        // not carry over what it never measured an effort for.
        aTreadmillRun(1, seconds = 600)
        aTreadmillRun(9, seconds = 3_600, finished = false)
        store.medals += Achievement(sessionId = 9, type = RecordType.LONGEST_DURATION, medal = Medal.GOLD, value = 3_600.0)

        book.seedFromHistory()

        assertEquals(listOf(9L to Medal.GOLD, 1L to Medal.SILVER), holders(RecordType.LONGEST_DURATION))
    }

    @Test
    fun `seeding reads what is owing before it measures, so a run finishing mid-pass stays owed`() = runTest {
        // Run 8 finishes while history is being measured. It scores itself — and if that scoring is
        // missed, only its own debt can find it.
        aTreadmillRun(1, seconds = 600)
        aTreadmillRun(8, seconds = 900, finished = false)
        store.whileMeasuring = { aTreadmillRun(8, seconds = 900) }

        book.seedFromHistory()

        assertEquals(setOf(1L), store.scored)
    }

    @Test
    fun `a reseeding pass takes the claims of a run it measured as worth nothing off the table`() = runTest {
        // The Walk mark committed and the re-banking behind it did not, so the seeding mark is still
        // down. A Walk contests nothing, so this pass measures it to nothing — which is the pass
        // having the last word on it, not failing to reach it — and its standing claim has to go.
        aTreadmillRun(1, seconds = 600)
        aTreadmillRun(5, seconds = 1_800, isWalk = true)
        store.efforts += RunEffortRow(5, RecordType.LONGEST_DURATION, 1_800.0)
        store.medals += Achievement(sessionId = 5, type = RecordType.LONGEST_DURATION, medal = Medal.GOLD, value = 1_800.0)

        book.seedFromHistory()

        // Carried on emptiness, every reseed would hand it its old half hour straight back.
        assertEquals(listOf(RunEffortRow(1, RecordType.LONGEST_DURATION, 600.0)), store.efforts)
        assertEquals(listOf(1L to Medal.GOLD), holders(RecordType.LONGEST_DURATION))
    }

    @Test
    fun `seeding stands down when a stated best effort changes while history is measured`() = runTest {
        aTreadmillRun(1, km = 6.0, seconds = 2_000)
        store.whileMeasuring = { store.stated += stated(1, RecordType.FASTEST_5K, 1_500) }

        book.seedFromHistory()

        assertTrue(store.medals.isEmpty())
        assertEquals(false, store.seeded)
        assertTrue(store.scored.isEmpty())
    }

    @Test
    fun `a run deleted while history is being scored leaves the pass owed again`() = runTest {
        aTreadmillRun(1, seconds = 600)
        aTreadmillRun(2, seconds = 1_800)
        store.whileMeasuring = { book.changeAndRepair(listOf(2L)) { store.delete(2L) } }

        book.seedFromHistory()

        // The book is written, but not marked: the delete read history as unseeded so it lifted no
        // mark of its own, and marking here would stand over a mend that can still be cut short.
        assertTrue(store.medals.isNotEmpty())
        assertEquals(false, store.seeded)
    }

    @Test
    fun `a delete already under way when seeding starts leaves the pass owed again`() = runTest {
        aTreadmillRun(1, seconds = 600)
        val gate = CompletableDeferred<Unit>()
        // Holds the delete open, and only the delete: the pass runs to completion beside it.
        store.beforeNextTransaction = { gate.await() }
        val deleting = launch { book.changeAndRepair(listOf(2L)) { store.delete(2L) } }
        runCurrent()

        book.seedFromHistory()

        // The delete was already counted when the pass took its baseline, so a count of starts
        // cannot see it. Only "one is running right now" can, and it is why the pass stays owed.
        assertEquals(false, store.seeded)

        gate.complete(Unit)
        deleting.join()
    }

    @Test
    fun `a delete cancelled mid-mend does not hold the mark down for good`() = runTest {
        aTreadmillRun(1, seconds = 600)
        store.beforeNextTransaction = { CompletableDeferred<Unit>().await() }

        // The runner leaves the history screen mid-delete and the view model's scope goes with them.
        val deleting = launch { book.changeAndRepair(listOf(2L)) { store.delete(2L) } }
        runCurrent()
        deleting.cancelAndJoin()

        book.seedFromHistory()

        // The count came down on the way out, so the pass that follows can still call the book
        // whole. Left up, no delete and no seeding pass in this process could ever mark it again.
        assertEquals(true, store.seeded)
    }

    // --- Mending the book when a Run changes or leaves (#50, #75, #231, #275, #282) ---

    @Test
    fun `deleting a medal holder moves the runs below it up and hands the seeding mark back`() = runTest {
        aTreadmillRun(1, km = 10.0, seconds = 3_000)
        aTreadmillRun(2, km = 12.0, seconds = 2_000)
        aTreadmillRun(3, km = 8.0, seconds = 1_000)
        book.seedFromHistory()
        store.events.clear()

        book.changeAndRepair(listOf(2L)) { store.delete(2L) }

        assertEquals(listOf(1L to Medal.GOLD, 3L to Medal.SILVER), holders(RecordType.LONGEST_DISTANCE))
        assertEquals(true, store.seeded)
        // The deletion is made durable before the minutes-long rebuild, so a process killed inside it
        // cannot leave a snapshot a restore would bring the deleted Run back from. The second
        // refresh carries the mended book out too.
        assertEquals(2, backups)
        assertEquals(
            listOf("backup", "medals written", "backup"),
            store.events.filter { it == "backup" || it == "medals written" },
        )
    }

    @Test
    fun `the medals a deleted run held are read in the transaction that removes it`() = runTest {
        book.changeAndRepair(listOf(2L)) { store.delete(2L) }

        // Both inside one transaction: a medal awarded by the seeding pass in between would be
        // cascaded away by the delete without ever showing up as a record to repair. The deleted
        // Run's claims are then re-banked in a transaction of their own (#75), which finds nothing.
        assertEquals(
            listOf("begin", "read medals", "delete", "commit", "begin", "commit"),
            store.events.filter { it in setOf("begin", "read medals", "delete", "commit") },
        )
    }

    @Test
    fun `deleting runs that won nothing leaves the book alone`() = runTest {
        aTreadmillRun(1, seconds = 3_600)
        aTreadmillRun(2, seconds = 60)
        aTreadmillRun(3, seconds = 3_000)
        aTreadmillRun(4, seconds = 2_400)
        aTreadmillRun(5, seconds = 30)
        book.seedFromHistory()
        val bookBefore = store.medals.toList()
        val readsBefore = store.historyReads

        book.changeAndRepair(listOf(2L, 5L)) { store.delete(2L); store.delete(5L) }

        // Not even measured: proving nothing changed must not cost a walk of the whole history.
        assertEquals(readsBefore, store.historyReads)
        assertEquals(bookBefore, store.medals)
    }

    @Test
    fun `the seeding debt is written down before the run is deleted`() = runTest {
        store.seeded = true

        book.changeAndRepair(listOf(2L)) { store.delete(2L) }

        // The debt goes down first, because everything after the delete can be cut short and a debt
        // recorded later is one those endings skip. Lifted even here, where the Run turns out to
        // have held nothing: what it held is not known until it is already gone.
        assertEquals(
            listOf("seeding owed", "delete", "backup", "seeded"),
            store.events.filter { it in setOf("seeding owed", "delete", "backup", "seeded") },
        )
    }

    @Test
    fun `a delete overtaken by another does not call the book whole`() = runTest {
        // With distances, so the mend has a record measured over ground and reads a track: that
        // read is the window the second delete lands in.
        aTreadmillRun(1, km = 3.0, seconds = 600)
        aTreadmillRun(2, km = 5.0, seconds = 1_800)
        book.seedFromHistory()
        // A second delete begins while the first is still measuring, which the history screen
        // allows, and does not get as far as mending anything.
        store.whileMeasuring = {
            runCatching { book.changeAndRepair(listOf(5L)) { error("the second delete fails") } }
        }

        book.changeAndRepair(listOf(2L)) { store.delete(2L) }

        assertEquals(false, store.seeded)
    }

    @Test
    fun `a mend that fails leaves history owing a full reseed`() = runTest {
        aTreadmillRun(1, seconds = 600)
        aTreadmillRun(2, seconds = 1_800)
        book.seedFromHistory()
        store.historyUnreadable = true

        book.changeAndRepair(listOf(2L)) { store.delete(2L) }

        // The medals went with the Run and the mend never landed, so the record stands short — and
        // only the top three are stored, so nothing but a full reseed can find the effort that
        // should move up.
        assertEquals(false, store.seeded)
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
    fun `a join that fails part-way leaves no phantom delete behind`() = runTest {
        aTreadmillRun(1, seconds = 600)
        aTreadmillRun(2, seconds = 1_800)
        book.seedFromHistory()
        // The first delete gets as far as joining the count and no further: lowering the mark throws.
        store.clearingTheSeededMarkFailsOnce = true

        assertTrue(runCatching { book.changeAndRepair(listOf(2L)) { store.delete(2L) } }.isFailure)
        book.changeAndRepair(listOf(2L)) { store.delete(2L) }

        // Nothing of the first delete is left standing in the count, so the second is the only one
        // there is and can hand the mark back. A phantom would have held it down for the life of
        // the process — a full reseed at every launch.
        assertEquals(true, store.seeded)
    }

    @Test
    fun `a delete on an install still owing its first seeding does not mark it done`() = runTest {
        aTreadmillRun(1, seconds = 600)
        aTreadmillRun(2, seconds = 1_800)
        store.medals += Achievement(sessionId = 2, type = RecordType.LONGEST_DURATION, medal = Medal.GOLD, value = 1_800.0)

        book.changeAndRepair(listOf(2L)) { store.delete(2L) }

        // A one-record repair is not the seeding pass, and must not cancel a debt it never paid.
        assertEquals(false, store.seeded)
        assertFalse("seeding owed" in store.events)
    }

    @Test
    fun `a rebuild rewrites the banked claims exactly as it rewrites the book`() = runTest {
        aTreadmillRun(1, km = 9.0, seconds = 1_200)
        aTreadmillRun(2, km = 12.0, seconds = 1_500)
        book.seedFromHistory()
        // A Run this pass did not measure — it finished while the measuring was going on and scored
        // itself. Its own claim has to survive the rewrite, exactly as its medal does.
        store.efforts += RunEffortRow(77, RecordType.LONGEST_DISTANCE, 30_000.0)

        book.changeAndRepair(listOf(2L), mendOnly = listOf(RecordType.LONGEST_DISTANCE)) {
            aTreadmillRun(2, km = 1.25, seconds = 1_500)
        }

        assertEquals(
            setOf(1L to 9_000.0, 2L to 1_250.0, 77L to 30_000.0),
            store.efforts.filter { it.type == RecordType.LONGEST_DISTANCE }.map { it.sessionId to it.value }.toSet(),
        )
    }

    @Test
    fun `a distance corrected downward promotes the run behind it`() = runTest {
        // The 9 km Run that was second exists nowhere but in history once the typo is corrected —
        // only the top three are banked, so re-scoring Run 2 alone could not find it.
        aTreadmillRun(1, km = 9.0, seconds = 1_200)
        aTreadmillRun(2, km = 12.0, seconds = 1_500)
        book.seedFromHistory()

        book.changeAndRepair(listOf(2L), mendOnly = listOf(RecordType.LONGEST_DISTANCE)) {
            aTreadmillRun(2, km = 1.25, seconds = 1_500)
        }

        assertEquals(listOf(1L to Medal.GOLD, 2L to Medal.SILVER), holders(RecordType.LONGEST_DISTANCE))
    }

    @Test
    fun `a withdrawn distance gives up the medal it held, rather than keeping it at a number the run no longer has`() = runTest {
        aTreadmillRun(1, km = 9.0, seconds = 1_200)
        aTreadmillRun(2, km = 12.0, seconds = 1_500)
        book.seedFromHistory()

        book.changeAndRepair(listOf(2L), mendOnly = listOf(RecordType.LONGEST_DISTANCE)) {
            aTreadmillRun(2, km = 0.0, seconds = 1_500)
        }

        // The book still held Run 2's gold while the rebuild measured, and a Run that now measures
        // to nothing must not have that old row carried back in as a claim of its own.
        assertEquals(listOf(1L to 9_000.0), store.medals.filter { it.type == RecordType.LONGEST_DISTANCE }.map { it.sessionId to it.value })
    }

    @Test
    fun `a slower stated time demotes the claim and promotes the run behind it`() = runTest {
        aTreadmillRun(1, km = 6.0, seconds = 1_800)
        aTreadmillRun(2, km = 6.0, seconds = 1_800)
        store.stated += stated(1, RecordType.FASTEST_5K, 1_440)
        store.stated += stated(2, RecordType.FASTEST_5K, 1_380)
        book.seedFromHistory()

        book.changeAndRepair(listOf(2L), mendOnly = listOf(RecordType.FASTEST_5K)) {
            store.state(stated(2, RecordType.FASTEST_5K, 1_500))
        }

        assertEquals(listOf(1L to Medal.GOLD, 2L to Medal.SILVER), holders(RecordType.FASTEST_5K))
    }

    @Test
    fun `a withdrawn best effort gives up the medal it held`() = runTest {
        aTreadmillRun(1, km = 6.0, seconds = 1_800)
        aTreadmillRun(2, km = 6.0, seconds = 1_800)
        store.stated += stated(1, RecordType.FASTEST_5K, 1_440)
        store.stated += stated(2, RecordType.FASTEST_5K, 1_380)
        book.seedFromHistory()

        book.changeAndRepair(listOf(2L), mendOnly = listOf(RecordType.FASTEST_5K)) {
            store.withdraw(2L, RecordType.FASTEST_5K)
        }

        // A Run that now claims nothing must not have its old row carried back in.
        assertEquals(
            listOf(1L to 1_440.0),
            store.medals.filter { it.type == RecordType.FASTEST_5K }.map { it.sessionId to it.value },
        )
    }

    @Test
    fun `a run marked a Walk gives up every medal it held`() = runTest {
        // A Walk contests nothing, so the Run that should move up exists nowhere but in history.
        aTreadmillRun(1, km = 3.0, seconds = 1_200)
        aTreadmillRun(2, km = 9.0, seconds = 3_600)
        book.seedFromHistory()

        book.changeAndRepair(listOf(2L)) { aTreadmillRun(2, km = 9.0, seconds = 3_600, isWalk = true) }

        assertEquals(listOf(1L to Medal.GOLD), holders(RecordType.LONGEST_DISTANCE))
        assertEquals(listOf(1L to Medal.GOLD), holders(RecordType.LONGEST_DURATION))
        assertTrue(store.effortsFor(2).isEmpty())
    }

    @Test
    fun `a Walk that never placed still loses its claims, and the snapshot is taken again after them`() = runTest {
        // The case the book cannot mend, because there is nothing in it to mend: fourth place holds
        // no medal, so no rebuild comes behind the change. Its banked claim is the only trace of it
        // left, and a Walk must not stand in a top ten. The snapshot taken as the mark landed
        // still holds that claim, so a restore from it would put the Walk straight back.
        aTreadmillRun(1, seconds = 3_600)
        aTreadmillRun(2, seconds = 3_000)
        aTreadmillRun(3, seconds = 2_400)
        aTreadmillRun(42, seconds = 1_800)
        book.seedFromHistory()

        book.changeAndRepair(listOf(42L)) { aTreadmillRun(42, seconds = 1_800, isWalk = true) }

        assertTrue(store.effortsFor(42).isEmpty())
        assertEquals(2, backups)
    }

    @Test
    fun `a change that leaves the banked claims where they were takes no second snapshot`() = runTest {
        // A Run marked a Walk before history was ever scored has nothing banked to lose, so the
        // snapshot taken when the mark landed is still accurate — and the backup is a whole copy of
        // history, so a second one would be paying for nothing.
        aTreadmillRun(42, seconds = 1_800)

        book.changeAndRepair(listOf(42L)) { aTreadmillRun(42, seconds = 1_800, isWalk = true) }

        assertEquals(1, backups)
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
    fun `a claim re-stated while its run's claims are re-taken keeps the newer banking`() = runTest {
        // A fourth-place stated 5K is withdrawn: no medal moves, so no rebuild will ever visit the
        // Record again. The runner states the time again while the re-banking is measuring the Run
        // without it; that statement banks the claim itself, and the older re-banking landing
        // afterwards would delete it.
        listOf(1L to 1_200, 2L to 1_250, 3L to 1_300).forEach { (id, seconds) ->
            aTreadmillRun(id, km = 6.0, seconds = 1_800)
            store.stated += stated(id, RecordType.FASTEST_5K, seconds)
        }
        aTreadmillRun(42, km = 6.0, seconds = 1_800)
        store.stated += stated(42, RecordType.FASTEST_5K, 1_380)
        book.seedFromHistory()

        book.changeAndRepair(listOf(42L), mendOnly = listOf(RecordType.FASTEST_5K)) {
            store.withdraw(42L, RecordType.FASTEST_5K)
            store.whileMeasuring = { store.state(stated(42, RecordType.FASTEST_5K, 1_380)) }
        }

        // Abandoned whole: the claim is still banked, and nothing was written, so no second copy of
        // history is bought for a pass that declined to write.
        assertTrue(RunEffortRow(42, RecordType.FASTEST_5K, 1_380.0) in store.effortsFor(42))
        assertEquals(1, backups)
    }
}
