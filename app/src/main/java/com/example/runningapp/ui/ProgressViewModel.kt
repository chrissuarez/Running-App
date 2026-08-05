package com.example.runningapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.training.ProgressDay
import com.example.runningapp.training.ProgressRange
import com.example.runningapp.training.TrainingWeek
import com.example.runningapp.training.WeeklyMeasure
import com.example.runningapp.training.progressCurve
import com.example.runningapp.training.weeklyVolumeOf
import com.example.runningapp.training.within
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The Progress screen's picture of training (#63, #64): today's three numbers, the stretch of curve
 * the chosen range shows, and the weeks of volume underneath it.
 *
 * [today] is null until there is a scored Run to build a curve from — a new phone, or a history the
 * backfill has not reached yet. The screen says so rather than drawing zeroes. [weeks] can be there
 * when [today] is not: a Run recorded without a Strap has no Score to curve, but it is still a week
 * of training.
 */
data class ProgressUiState(
    val range: ProgressRange = ProgressRange.THREE_MONTHS,
    val today: ProgressDay? = null,
    val curve: List<ProgressDay> = emptyList(),
    val measure: WeeklyMeasure = WeeklyMeasure.DISTANCE,
    val weeks: List<TrainingWeek> = emptyList(),
)

class ProgressViewModel(
    sessionRepository: SessionRepository,
    /** The zone the runner's calendar days are in — which day a Run belongs to depends on it. */
    private val zone: ZoneId = ZoneId.systemDefault(),
    /**
     * What day it is, asked each time the curve is built rather than fixed at construction.
     *
     * A screen left open across midnight keeps yesterday's last day until something moves — a Score
     * landing, or the screen being opened again. That is the honest thing to show: nothing has been
     * measured on the new day yet, and the curve would only gain a day of rest nobody has taken.
     */
    private val today: () -> LocalDate = { LocalDate.now(zone) },
    /** Where the curves are worked out — anywhere but the thread drawing them. */
    curveDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /**
     * The range every chart on this screen shares — the Fitness/Fatigue curve here, and the weekly
     * volume chart to come. One pick, one window.
     */
    private val _range = MutableStateFlow(ProgressRange.THREE_MONTHS)

    /**
     * What the weekly bars are counting. Distance to begin with: it is the number a runner states a
     * week in without being asked which one they mean.
     */
    private val _measure = MutableStateFlow(WeeklyMeasure.DISTANCE)

    /**
     * The whole curve, from the runner's first Run to today.
     *
     * Built off the main thread ([Dispatchers.Default]): a year of history is a few hundred days of
     * arithmetic and a decade is a few thousand, which is nothing on its own but is not work the
     * frame drawing it should be doing. Kept whole and windowed below, so switching range is a
     * filter rather than a recomputation.
     *
     * Eagerly, because this view model is made when the Progress screen is opened and dies with it
     * — there is no idle stretch for a lazier start to save anything in, and starting on the way to
     * the screen is a curve that is ready when it arrives.
     */
    private val curve: StateFlow<List<ProgressDay>> = sessionRepository.scoredRunsFlow()
        .map { runs -> progressCurve(runs, through = today(), zone = zone) }
        .flowOn(curveDispatcher)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Every week of training from the runner's first Run to today, built and windowed exactly as the
     * curve above is — whole on one side of the range picker, filtered on the other. Switching
     * between distance, time and Effort is then a re-read of weeks already totalled rather than
     * three rollups kept in step.
     */
    private val weeks: StateFlow<List<TrainingWeek>> = sessionRepository.runVolumesFlow()
        .map { runs -> weeklyVolumeOf(runs, through = today(), zone = zone) }
        .flowOn(curveDispatcher)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The window is measured back from the curve's own last day rather than from today asked afresh.
     * The two are the same day except across a midnight the screen was left open through, and there
     * the curve is what the numbers above it were read off — a window ending on a day the curve does
     * not reach would put "today's" numbers under a chart that stops short of them.
     *
     * The weeks are windowed against that same day, so one pick moves both charts to the same
     * stretch of time. Where there is no curve to take it from — Runs recorded without a Strap —
     * the last week the runner has stands in, which is the same day by another route.
     */
    val state: StateFlow<ProgressUiState> =
        combine(curve, weeks, _range, _measure) { curve, weeks, range, measure ->
            val lastDay = curve.lastOrNull()
            val endingOn = lastDay?.date ?: weeks.lastOrNull()?.startingOn
            ProgressUiState(
                range = range,
                today = lastDay,
                curve = lastDay?.let { curve.within(range, endingOn = it.date) } ?: emptyList(),
                measure = measure,
                weeks = endingOn?.let { weeks.within(range, endingOn = it) } ?: emptyList(),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ProgressUiState())

    fun rangeChosen(range: ProgressRange) {
        _range.value = range
    }

    fun measureChosen(measure: WeeklyMeasure) {
        _measure.value = measure
    }
}

class ProgressViewModelFactory(
    private val sessionRepository: SessionRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProgressViewModel::class.java)) {
            return ProgressViewModel(sessionRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
