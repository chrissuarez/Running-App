package com.example.runningapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.SettingsRepository
import com.example.runningapp.suggestedMaxHr
import com.example.runningapp.data.GoalDao
import com.example.runningapp.data.GoalRow
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.training.Goal
import com.example.runningapp.training.GoalMetric
import com.example.runningapp.training.GoalPeriod
import com.example.runningapp.training.GoalProgress
import com.example.runningapp.training.ProgressDay
import com.example.runningapp.training.ProgressRange
import com.example.runningapp.training.TrainingWeek
import com.example.runningapp.training.VolumeRun
import com.example.runningapp.training.WeeklyMeasure
import com.example.runningapp.training.goalProgressOf
import com.example.runningapp.training.progressCurve
import com.example.runningapp.training.weeklyVolumeOf
import com.example.runningapp.training.within
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    /**
     * The one-time Max HR confirmation (#65), or null whenever it is not the runner's to answer:
     * they have answered it, they have put it away, or their own recorded evidence has not been
     * read back yet. Null is much the commonest state — this is asked once in the life of an
     * install.
     */
    val maxHrCard: MaxHrCardState? = null,
    /**
     * The runner's goals and where they stand today (#82) — empty when they have set none, which is
     * the state the card offers "Set a goal" from rather than a state that hides it.
     */
    val goals: List<GoalProgress> = emptyList(),
)

/**
 * The charts' half of [ProgressUiState] — the four flows that have to be read together because the
 * range windows all of them against one day.
 *
 * Split out only so that the goals and the Max HR card can be combined in beside them: `combine`
 * takes five flows at most, and grouping the ones that already depend on each other is better than
 * an array of untyped flows.
 */
private data class ChartsUiState(
    val range: ProgressRange,
    val today: ProgressDay?,
    val curve: List<ProgressDay>,
    val measure: WeeklyMeasure,
    val weeks: List<TrainingWeek>,
)

/**
 * The weeks of training and the day they were totalled through — the day their range has to be
 * measured back from, kept with them so the two cannot drift apart.
 *
 * [through] is null only before the first read of the history has come back.
 */
private data class VolumeToDate(val through: LocalDate?, val weeks: List<TrainingWeek>)

/**
 * The answer to "what is the highest heart rate this phone has recorded", once it has been asked —
 * where [bpm] null is the answer "none", and no wrapper at all is the question still outstanding.
 */
private data class HighestRecordedHr(val bpm: Int?)

class ProgressViewModel(
    sessionRepository: SessionRepository,
    /**
     * Where the confirmation card's answer is remembered — the dismissal only. The number itself
     * goes through [stateHeartRates], never written here: this screen is one more surface stating a
     * heart rate, and every one of them goes through the single door that keeps the number and the
     * history banded against it in step.
     */
    private val settingsRepository: SettingsRepository,
    /**
     * States the runner's Max HR, in the order it was stated in — `AppContainer.stateHeartRates`.
     * Does not suspend, and deliberately: the first statement re-works the whole of history behind
     * it, and the card is answered and gone before that finishes.
     */
    private val stateHeartRates: (Int?, Int?) -> Unit,
    /** Where the runner's Goals are kept (#82). */
    private val goalDao: GoalDao,
    /**
     * The zone the runner's calendar days are in — which day a Run belongs to depends on it.
     *
     * Asked each time a chart is built, never held (#299): the day the app is in is observed. A
     * screen left open while the phone crosses into another zone is redrawn where the runner is,
     * not where they opened it.
     */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /**
     * What day it is, asked each time the curve is built rather than fixed at construction.
     *
     * A screen left open across midnight keeps yesterday's last day until something moves — a Score
     * landing, or the screen being opened again. That is the honest thing to show: nothing has been
     * measured on the new day yet, and the curve would only gain a day of rest nobody has taken.
     */
    private val today: () -> LocalDate = { LocalDate.now(zone()) },
    /** What time it is, for stamping a goal with when it was set. */
    private val now: () -> Long = { System.currentTimeMillis() },
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
        .map { runs -> progressCurve(runs, through = today(), zone = zone()) }
        .flowOn(curveDispatcher)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Every finished Run in history, as the volume bars and the goals both see it — read once and
     * shared. Two readers of the same list rather than two queries: they are asking the same
     * question of the same table, and a second stream would answer it a moment apart from the first.
     */
    private val volumeRuns: StateFlow<List<VolumeRun>> = sessionRepository.runVolumesFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Every week of training from the runner's first Run to today, built and windowed exactly as the
     * curve above is — whole on one side of the range picker, filtered on the other. Switching
     * between distance, time and Effort is then a re-read of weeks already totalled rather than
     * three rollups kept in step.
     */
    private val weeks: StateFlow<VolumeToDate> = volumeRuns
        .map { runs ->
            val through = today()
            VolumeToDate(through, weeklyVolumeOf(runs, through = through, zone = zone()))
        }
        .flowOn(curveDispatcher)
        .stateIn(viewModelScope, SharingStarted.Eagerly, VolumeToDate(through = null, weeks = emptyList()))

    /**
     * Where each goal stands in the period the runner is in now (#82).
     *
     * Read off the same Runs the bars are totalled from, and worked out on read every time: a goal
     * edited, a Run deleted, a treadmill distance stated late — each of them lands here by being a
     * new read rather than by anything remembering to correct a stored total.
     */
    private val goals: Flow<List<GoalProgress>> =
        combine(goalDao.getAllGoalsFlow(), volumeRuns) { rows, runs ->
            goalProgressOf(rows.map { it.toGoal() }, runs, on = today(), zone = zone())
        }.flowOn(curveDispatcher)

    /**
     * The runner's own highest recorded heart rate, read once when the screen opens — and null
     * until that read comes back, which is a different thing from a phone with nothing recorded
     * (that is `HighestRecordedHr(null)`).
     *
     * The difference is the whole reason for the wrapper. Read as "no history", the moment before
     * the answer arrives would draw the age-fallback card, and a runner who tapped in that moment
     * would be asked their age with 181 BPM of their own evidence sitting unread. So the card waits
     * for the read rather than guessing at it — a few milliseconds, once, on a card that is asked
     * once in the life of an install.
     *
     * Stays null for good on a phone whose card is already retired, because there the read is not
     * worth making — see the gate below.
     */
    private val recordedPeak = MutableStateFlow<HighestRecordedHr?>(null)

    init {
        viewModelScope.launch {
            // Asked only where an answer could still be offered. The card is retired for good once
            // either flag is set, and the peak is a sort over the whole of `hr_samples` — read
            // unconditionally it would be a full-history scan on every visit to this screen, for a
            // card that can never be drawn again. Left unread, this stays null and so does the card.
            val settings = settingsRepository.userSettingsFlow.first()
            if (settings.maxHrEverSet || settings.maxHrCardDismissed) return@launch
            recordedPeak.value = HighestRecordedHr(sessionRepository.highestRecordedHr())
        }
    }

    /**
     * Whether the runner is being asked to confirm their Max HR, and what the card offers if so
     * (#65, #103).
     *
     * Two flags hide it and either is enough: `maxHrEverSet` — they have stated a maximum, here or
     * in Settings, so the question is answered — and `maxHrCardDismissed`, which is them having put
     * it away without answering. Read as a stream rather than once, so the card leaves the screen
     * as soon as the answer lands rather than at the next visit.
     */
    /**
     * Whether the runner has answered the card on this visit and the statement is still on its way
     * through the queue — which is not the same as it having arrived (#65).
     *
     * The card has to leave the screen the moment it is answered, or a second answer can be given to
     * a question already answered while history re-bands behind the first. But it must not be put
     * away *for good* on the strength of an answer that has not landed: a statement can be dropped,
     * and a card retired against a dropped one leaves the runner on the placeholder 190 with no way
     * left to be asked. So this hides it here and now, and nothing else — the next visit reads the
     * stored flags and asks again if the answer never made it.
     */
    private val answeringNow = MutableStateFlow(false)

    private val maxHrCard: Flow<MaxHrCardState?> = combine(
        settingsRepository.userSettingsFlow,
        recordedPeak,
        answeringNow
    ) { settings, peak, answering ->
        if (answering) null
        else if (peak == null) null
        else if (settings.maxHrEverSet || settings.maxHrCardDismissed) null
        else MaxHrCardState(
            currentMaxHr = settings.maxHr,
            restingHr = settings.restingHr,
            suggestedMaxHr = suggestedMaxHr(peak.bpm, settings.restingHr),
        )
    }

    /**
     * The window is measured back from the curve's own last day rather than from today asked afresh.
     * The two are the same day except across a midnight the screen was left open through, and there
     * the curve is what the numbers above it were read off — a window ending on a day the curve does
     * not reach would put "today's" numbers under a chart that stops short of them.
     *
     * The weeks are windowed against that same day, so one pick moves both charts to the same
     * stretch of time. Where there is no curve to take it from — Runs recorded without a Strap —
     * the day the weeks themselves were totalled through stands in, which is the same day by another
     * route. Not the last week's Monday: on any day but a Monday that would measure the range back
     * from up to six days early and let in a leading week the runner did not ask for.
     */
    private val charts: Flow<ChartsUiState> =
        combine(curve, weeks, _range, _measure) { curve, volume, range, measure ->
            val lastDay = curve.lastOrNull()
            val endingOn = lastDay?.date ?: volume.through
            ChartsUiState(
                range = range,
                today = lastDay,
                curve = lastDay?.let { curve.within(range, endingOn = it.date) } ?: emptyList(),
                measure = measure,
                weeks = endingOn?.let { volume.weeks.within(range, endingOn = it) } ?: emptyList(),
            )
        }

    val state: StateFlow<ProgressUiState> =
        combine(charts, maxHrCard, goals) { charts, card, goals ->
            ProgressUiState(
                range = charts.range,
                today = charts.today,
                curve = charts.curve,
                measure = charts.measure,
                weeks = charts.weeks,
                maxHrCard = card,
                goals = goals,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ProgressUiState())

    /**
     * Sets a goal, or edits the one already standing for that period and metric (#82).
     *
     * The target is stated in the metric's own unit, and nothing is worked out here: the bar the
     * runner sees next is a fresh read of their Runs against the new number, so a raised target shows
     * a week honestly unfinished rather than a bar that stayed full from the old one.
     */
    fun goalSet(period: GoalPeriod, metric: GoalMetric, target: Double) {
        viewModelScope.launch {
            // The goal this replaces, so an edit stays the same goal: its own id, and the day it was
            // first set. Without them a corrected target would be a brand new goal, and the card
            // would shuffle it to the bottom for having been edited.
            val standing = goalDao.goalFor(period, metric)
            goalDao.setGoal(
                GoalRow(
                    id = standing?.id ?: 0,
                    period = period,
                    metric = metric,
                    target = target,
                    createdAtMillis = standing?.createdAtMillis ?: now(),
                )
            )
        }
    }

    /** Removes a goal. Nothing else goes with it — no Run has ever been keyed to one. */
    fun goalRemoved(goal: Goal) {
        viewModelScope.launch { goalDao.deleteGoal(goal.id) }
    }

    fun rangeChosen(range: ProgressRange) {
        _range.value = range
    }

    fun measureChosen(measure: WeeklyMeasure) {
        _measure.value = measure
    }

    /**
     * The runner has stated their Max HR from the card — either the number they typed, or the one
     * their zones are already on, which is a statement too (#103).
     *
     * The number goes to the queue that keeps statements ordered and re-works history behind the
     * first one, and the card leaves the screen at once — but only for this visit. What retires it
     * for good is `maxHrEverSet`, which the statement itself sets, so an answer that never lands
     * leaves the question askable rather than the runner stranded on a maximum nobody chose.
     */
    fun maxHrConfirmed(maxHr: Int) {
        answeringNow.value = true
        stateHeartRates(maxHr, null)
    }

    /**
     * The card closed with nothing stated. Forever, and recorded as such: being asked once is the
     * whole design, and a runner who declined to answer must not be asked again for having declined.
     */
    fun maxHrCardDismissed() {
        answeringNow.value = true
        viewModelScope.launch { settingsRepository.setMaxHrCardDismissed() }
    }
}

class ProgressViewModelFactory(
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val stateHeartRates: (Int?, Int?) -> Unit,
    private val goalDao: GoalDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProgressViewModel::class.java)) {
            return ProgressViewModel(sessionRepository, settingsRepository, stateHeartRates, goalDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
