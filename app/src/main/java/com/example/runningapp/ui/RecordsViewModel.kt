package com.example.runningapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.data.RecordBook
import com.example.runningapp.data.RecordEffortRow
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.repeatedOn
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The Records section and the pages behind it (#75).
 *
 * One rule for both: the grid on the Progress screen and any Record's own page are built from the
 * same query ([SessionRepository.recordBookFlow]) and placed by the same order, so the number in
 * a slot and the gold at the top of that slot's page cannot be two different answers. Each screen
 * builds its own instance of this — a view model belongs to the screen that opened it — and that
 * costs nothing but a second read of a small table, because the rule they share is in the placing
 * rather than in the instance.
 *
 * Nothing is measured here and nothing is stored. Every row is a claim the record book already
 * banked as it scored the Run ([com.example.runningapp.data.RunEffortRow]); what this does is place
 * them and put them into words ([recordSlots], [recordTopEfforts], [recordTrendPoints]).
 */
class RecordsViewModel(
    private val sessionRepository: SessionRepository,
    /**
     * The zone the runner's calendar days are in — which day an effort belongs to depends on it.
     *
     * Asked each time rather than held (#299), for the reason the Progress screen's charts ask it
     * each time: the day the app is in is observed, and a screen left open while the phone crosses
     * into another zone is redrawn where the runner is.
     */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /**
     * The phone changing zone, which is what makes the dates below redraw (#320).
     *
     * Required rather than defaulted to an empty flow, for the reason `ProgressViewModel` requires
     * it: a default would be a silent opt-out, and a screen built later without it would quietly be
     * back to the bug this closed.
     */
    private val zoneChanges: Flow<Unit>,
    /** Where the placing and the wording happen — anywhere but the thread drawing them. */
    private val recordsDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /**
     * The record book — every claim ever banked, and whether history is being measured against it
     * wholesale — offered again whenever the phone changes zone; or null, which means Room has not
     * answered yet (#75).
     *
     * Shared rather than collected twice, because the grid and a Record's page ask the same question
     * of the same tables and a second stream would answer it a moment apart from the first.
     *
     * **One value, not the claims and the flag watched apart (#346).** They were once two flows
     * combined here, and Room re-reads each table on its own: the one-row flag answered "nothing
     * owed" the moment the fill was handed back, while this still held the claims read part-way
     * through it, and for that moment the grid drew a top ten off a slice of history. The repository
     * now reads both in one statement ([SessionRepository.recordBookFlow]), so a lowered flag can
     * only reach here beside the claims written before it was lowered.
     *
     * **Null and not an empty book, and this is the whole of the rule.** A shared state has to be
     * seeded with something before its query answers, and a seed of "no claims" is not a placeholder
     * — it is a sentence, and the sentence is "this runner has never run anything". Everything
     * downstream then says it out loud in its own words: the grid draws seven slots reading "Not run
     * yet", and a Record's page prints the message that nobody has ever contested it, both of them a
     * frame or two after the runner tapped a number saying otherwise. The seed is the earliest place
     * the difference between "nothing" and "not asked yet" can be told, so it is told here once and
     * every reader below derives its own not-yet from this one fact rather than inventing another.
     *
     * The same shape [RecordDetailUi.top] already carries for the same reason, and for the reason it
     * gives: the absence of the rows says "not read" better than a flag beside them, because a flag
     * is a second answer to a question the rows themselves answer and two answers can disagree.
     * Being still measured ([RecordBook.measuring]) is a third fact again and survives untouched —
     * there the read *has* answered and the answer is deliberately nothing.
     */
    private val book: StateFlow<RecordBook?> = sessionRepository.recordBookFlow()
        .repeatedOn(zoneChanges)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The three states any reading of the record book can be in, said once for every reader (#75).
     *
     * A reading is one of exactly three things and the runner is owed a different thing by each:
     * the book has not answered yet ([whileUnread]) and the screen must say nothing at all;
     * history is being measured against the book wholesale ([whileMeasuring]) and the screen says so
     * in words; or the rows are in hand and the screen is read off them ([read]).
     *
     * One function rather than the same three branches written out in the grid and again on a
     * Record's page, because that is how the two came apart in the first place: a reader left to
     * decide for itself what "no rows yet" means is a reader one `emptyList()` away from telling the
     * runner their records are gone. Both readings below are this function with different words, so
     * a fourth screen added later gets the rule by using it rather than by remembering it.
     *
     * The flag and the claims arrive together (#346), so "not answered" is simply the absence of a
     * book, and a book that has answered is read by its own flag: whether a fill is outstanding is
     * the database's word, and the claims beside it are never a slice ([RecordBook.efforts]). For
     * what the flag keys on — and why it cannot fire in ordinary use, after every Run, for ever —
     * see [SessionRepository.recordsBeingMeasuredFlow].
     */
    private fun <T> reading(
        whileUnread: T,
        whileMeasuring: T,
        read: (List<RecordEffortRow>) -> T,
    ): Flow<T> = book.map { book ->
        when {
            book == null -> whileUnread
            book.measuring -> whileMeasuring
            else -> read(book.efforts)
        }
    }
        .flowOn(recordsDispatcher)

    /**
     * The Records grid: every Record, best first at each — or the fact that they are still being
     * measured, or nothing at all because the table has not answered yet (#75).
     *
     * One reading of one book rather than two states the screen collects apart, so the slots and the
     * flag are one answer: the moment the flag stands, what goes with it is an empty grid and not a grid read off
     * the slice of history the table has reached. A partial top ten hands out medals to Runs that do
     * not place, which is worse than a section that says what it is doing.
     *
     * Seeded with [recordsGridNotReadYet] and not with a grid of empty slots, which is the same
     * distinction [book] is seeded on: [recordSlots] always hands back all seven Records, so a
     * grid of seven slots reading "Not run yet" is a claim about the runner's history and must never
     * be what a screen is handed before that history has been read.
     */
    val grid: StateFlow<RecordsGridUi> = reading(
        whileUnread = recordsGridNotReadYet(),
        // Read, and deliberately nothing: the section keeps its heading and says what it is doing.
        whileMeasuring = RecordsGridUi(slots = emptyList(), measuring = true),
        read = { rows -> RecordsGridUi(slots = recordSlots(rows, zone())) },
    )
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), recordsGridNotReadYet())

    /**
     * One Record's whole page, watched: a Run finishing, a treadmill time stated, a Run deleted or
     * marked a Walk all move what stands here while the page is open.
     *
     * The first thing it hands over is [recordDetailNotReadYet] — the very value the screen opens
     * itself on — so that the page the runner tapped into stays silent until there is something true
     * to say, rather than being overwritten a frame later with "you have never covered 5 km" (#75).
     */
    fun detail(type: RecordType): Flow<RecordDetailUi> = reading(
        whileUnread = recordDetailNotReadYet(type),
        // Nothing placed and nothing charted while history is still being measured, for the grid's
        // reason: fourth place read off a slice of history is a place the runner never took, and
        // this page is the one that prints places down to tenth (#75).
        whileMeasuring = RecordDetailUi(
            type = type,
            top = emptyList(),
            trend = emptyList(),
            effortCount = 0,
            measuring = true,
        ),
        read = { rows ->
            val zone = zone()
            RecordDetailUi(
                type = type,
                top = recordTopEfforts(rows, type, zone),
                trend = recordTrendPoints(rows, type, zone),
                effortCount = rows.count { it.type == type },
            )
        },
    )
}

class RecordsViewModelFactory(
    private val sessionRepository: SessionRepository,
    /** The phone changing zone — see [RecordsViewModel]'s own parameter of the same name (#320). */
    private val zoneChanges: Flow<Unit>,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RecordsViewModel::class.java)) {
            return RecordsViewModel(sessionRepository, zoneChanges = zoneChanges) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
