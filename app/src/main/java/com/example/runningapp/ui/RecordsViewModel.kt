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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

    /** The Records grid: every Record, best first at each. */
    val slots: StateFlow<List<RecordSlotUi>> = efforts
        .map { rows -> recordSlots(rows, zone()) }
        .flowOn(recordsDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * One Record's whole page, watched: a Run finishing, a treadmill time stated, a Run deleted or
     * marked a Walk all move what stands here while the page is open.
     */
    fun detail(type: RecordType): Flow<RecordDetailUi> = efforts
        .map { rows ->
            val zone = zone()
            RecordDetailUi(
                type = type,
                top = recordTopEfforts(rows, type, zone),
                trend = recordTrendPoints(rows, type, zone),
                effortCount = rows.count { it.type == type },
            )
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
