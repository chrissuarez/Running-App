package com.example.runningapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.repeatedOn
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * The Records section and the pages behind it (#75).
 *
 * One rule for both: the grid on the Progress screen and any Record's own page are built from the
 * same query ([SessionRepository.recordEffortsFlow]) and placed by the same order, so the number in
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
     * Every claim ever banked, offered again whenever the phone changes zone.
     *
     * Shared rather than collected twice, because the grid and a Record's page ask the same question
     * of the same table and a second stream would answer it a moment apart from the first.
     */
    private val efforts = sessionRepository.recordEffortsFlow()
        .repeatedOn(zoneChanges)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whether history is being measured against the book wholesale right now (#75).
     *
     * Watched alongside the claims themselves and folded into both readings below, so neither the
     * grid nor a Record's page can print a number off a table that is still filling. The argument
     * for what this keys on — and why it cannot fire in ordinary use, after every Run, for ever —
     * is on [SessionRepository.recordsBeingMeasuredFlow].
     */
    private val measuring = sessionRepository.recordsBeingMeasuredFlow()

    /**
     * The Records grid: every Record, best first at each — or the fact that they are still being
     * measured, and no numbers at all (#75).
     *
     * [combine] rather than two states the screen collects apart, so the slots and the flag are one
     * answer: the moment the flag stands, what goes with it is an empty grid and not a grid read off
     * the slice of history the table has reached. A partial top ten hands out medals to Runs that do
     * not place, which is worse than a section that says what it is doing.
     */
    val grid: StateFlow<RecordsGridUi> = combine(efforts, measuring) { rows, stillMeasuring ->
        if (stillMeasuring) RecordsGridUi(measuring = true)
        else RecordsGridUi(slots = recordSlots(rows, zone()))
    }
        .flowOn(recordsDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordsGridUi())

    /**
     * One Record's whole page, watched: a Run finishing, a treadmill time stated, a Run deleted or
     * marked a Walk all move what stands here while the page is open.
     */
    fun detail(type: RecordType): Flow<RecordDetailUi> =
        combine(efforts, measuring) { rows, stillMeasuring ->
            val zone = zone()
            // Nothing placed and nothing charted while history is still being measured, for the
            // grid's reason: fourth place read off a slice of history is a place the runner never
            // took, and this page is the one that prints places down to tenth (#75).
            if (stillMeasuring) {
                RecordDetailUi(type = type, top = emptyList(), trend = emptyList(), effortCount = 0, measuring = true)
            } else {
                RecordDetailUi(
                    type = type,
                    top = recordTopEfforts(rows, type, zone),
                    trend = recordTrendPoints(rows, type, zone),
                    effortCount = rows.count { it.type == type },
                )
            }
        }
            .flowOn(recordsDispatcher)
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
