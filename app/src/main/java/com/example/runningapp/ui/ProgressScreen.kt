package com.example.runningapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.runningapp.training.FormVerdict
import com.example.runningapp.training.ProgressDay
import com.example.runningapp.training.ProgressRange
import com.example.runningapp.training.TrainingWeek
import com.example.runningapp.training.WeeklyMeasure
import com.example.runningapp.training.formVerdictOf
import com.example.runningapp.ui.theme.RunningAppTheme
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.line.lineSpec
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec
import com.patrykandpatrick.vico.compose.component.lineComponent
import com.patrykandpatrick.vico.compose.m3.style.m3ChartStyle
import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.core.axis.AxisItemPlacer
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.component.shape.Shapes
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.entryOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The colours the two curves are read by, here and in the key beneath them.
 *
 * Blue and amber rather than the green and red these numbers are usually drawn in: red/green is the
 * one pair a colour-blind runner cannot separate, and there is nothing else on the chart to tell the
 * curves apart by. They also differ in lightness, so the pair survives being seen in sunlight.
 */
private val FitnessColour = Color(0xFF1565C0)
private val FatigueColour = Color(0xFFEF6C00)

/**
 * The weekly bars. A third colour rather than either curve's, because the bars are not a third
 * reading of the same thing — Fitness and Fatigue are what training cost, and volume is what was
 * done. Teal is far enough from both to be told apart at a glance and, unlike a green, is still
 * distinguishable from the amber above it without relying on hue alone.
 */
private val VolumeColour = Color(0xFF00796B)

private val AxisDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/**
 * How the runner's training is going (#63, #64): today's Fitness, Fatigue and Form in words and
 * numbers, the two curves that got them there, and the weeks of volume underneath.
 *
 * The numbers come first and the chart second on purpose — the runner should be able to get the
 * verdict without reading a chart at all (spec #60, story 7). The weeks come last because they are
 * the record of what was done, read after the verdict on what it added up to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    state: ProgressUiState,
    onRangeChosen: (ProgressRange) -> Unit,
    onMeasureChosen: (WeeklyMeasure) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Progress") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // An if/else and never an early `return@Column` from this lambda: the curve arrives a
            // moment after the screen opens, so the empty branch is what every visit composes first
            // and the filled one is what replaces it. A return out of a composable lambda skips the
            // groups the compiler opened for the rest of it, and the composition that follows reads
            // a slot table that no longer describes itself — on the phone, an
            // `ArrayIndexOutOfBoundsException` inside Scaffold as the screen appeared.
            val today = state.today
            if (today == null && state.weeks.isEmpty()) {
                Text(
                    "No finished runs yet. Once a run is recorded, your training shows up here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                if (today == null) {
                    // Weeks without a curve: every Run so far was recorded without heart rate, so
                    // there is volume to show and nothing to score it with. The weeks below are
                    // still the runner's training, so they are drawn rather than withheld.
                    Text(
                        "No scored runs yet. Once a run with heart rate is recorded, your Fitness, " +
                            "Fatigue and Form appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    TodayCard(today)
                }
                RangePicker(selected = state.range, onRangeChosen = onRangeChosen)
                if (today != null) {
                    FitnessFatigueChart(days = state.curve)
                    ChartKey()
                }
                if (state.weeks.isNotEmpty()) {
                    WeeklyVolume(
                        weeks = state.weeks,
                        measure = state.measure,
                        onMeasureChosen = onMeasureChosen,
                    )
                }
            }
        }
    }
}

/** Today's three numbers, and what the Form one means in a word. */
@Composable
private fun TodayCard(today: ProgressDay) {
    val verdict = formVerdictOf(today.form)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Metric("Fitness", today.fitness)
                Metric("Fatigue", today.fatigue)
                Metric("Form", today.form)
            }
            Text(
                text = verdictLineOf(verdict),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * The one-line reading of Form: the band's own word, and what it says about the runner.
 *
 * A description and never advice. What to do about being fatigued is the coach's to say, on the
 * screen the coach speaks from — this screen reports.
 */
private fun verdictLineOf(verdict: FormVerdict): String = when (verdict) {
    FormVerdict.FRESH -> "Fresh — you're carrying less fatigue than fitness."
    FormVerdict.NEUTRAL -> "Neutral — fitness and fatigue are close to balanced."
    FormVerdict.FATIGUED -> "Fatigued — you're carrying more fatigue than fitness."
}

@Composable
private fun Metric(label: String, value: Double) {
    // Whole numbers: a Fitness of 31.4 is not measured to a tenth of anything, and a runner reading
    // three of these side by side is comparing sizes, not decimals.
    val shown = value.roundToInt()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "$shown", style = MaterialTheme.typography.headlineMedium)
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RangePicker(selected: ProgressRange, onRangeChosen: (ProgressRange) -> Unit) {
    ChipPicker(
        options = ProgressRange.entries,
        selected = selected,
        labelOf = { it.label },
        onChosen = onRangeChosen,
    )
}

/**
 * A row of chips to pick one of a short list by.
 *
 * A FlowRow and not a Row: the narrowest screen this has to survive is 320dp with the text scaled
 * to 1.3×, and "Distance / Time / Effort Score" does not fit across it. In a Row the last chip is
 * simply cut off the edge with no way to reach it; wrapped, it drops to a second line.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipPicker(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onChosen: (T) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onChosen(option) },
                label = { Text(labelOf(option)) },
            )
        }
    }
}

/**
 * Three dates along the bottom of a chart, whatever the range.
 *
 * One label per entry is what Vico would otherwise attempt, and at a day apart there is no room for
 * "5 May" — every label came out as "5 …", a chart of days that never says which month it is in.
 * Three and not more because of the same 320dp-at-1.3× screen: at four the months went back to
 * being cut short there. The half-step offset gives the first date the chart's edge to spread into;
 * labelled from entry one it is the axis's own left edge that cuts it, and "5 Aug" arrived as "..".
 */
private fun threeLabelPlacer(entries: Int): AxisItemPlacer.Horizontal {
    val labelEvery = (entries / 3).coerceAtLeast(1)
    return AxisItemPlacer.Horizontal.default(
        spacing = labelEvery,
        offset = labelEvery / 2,
        addExtremeLabelPadding = true,
    )
}

/**
 * The two curves over the chosen range.
 *
 * The chart is fed by index rather than by date so that the x axis is evenly spaced day by day —
 * the curve has a point for every calendar day including rest days, so the index *is* the date, and
 * the bottom axis turns it back into one to label with.
 */
@Composable
private fun FitnessFatigueChart(days: List<ProgressDay>) {
    // A guard on [days.first] below rather than a state the runner can reach: every range ends on
    // the curve's own last day, so a curve that exists has at least that one day in every window,
    // and a curve that does not exist never gets this far ([ProgressScreen]).
    if (days.isEmpty()) return

    // The days are handed to the producer as it is built, not pushed into an empty one from a
    // LaunchedEffect afterwards. An effect runs after the frame it was composed in, so the chart got
    // measured once against no data at all — and a Vico chart measured with an empty model throws
    // (`ChartValuesProvider.Empty#getChartValues shouldn't be used`). Rebuilt whenever the days
    // change, which is a new range or a new Score landing.
    val producer = remember(days) {
        ChartEntryModelProducer(
            days.mapIndexed { index, day -> entryOf(index.toFloat(), day.fitness.toFloat()) },
            days.mapIndexed { index, day -> entryOf(index.toFloat(), day.fatigue.toFloat()) },
        )
    }

    val firstDay = days.first().date
    val dateLabels = AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
        firstDay.plusDays(value.toLong()).format(AxisDateFormat)
    }
    // Whole numbers up the side, to read against the whole numbers in the card above. Vico's own
    // formatter would label these 46.71 and 42.04, which is a precision none of this is measured to.
    val wholeNumbers = AxisValueFormatter<AxisPosition.Vertical.Start> { value, _ ->
        value.roundToInt().toString()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .semantics {
                contentDescription = "Fitness and Fatigue from " +
                    "${firstDay.format(AxisDateFormat)} to ${days.last().date.format(AxisDateFormat)}"
            }
    ) {
        ProvideChartStyle(m3ChartStyle()) {
            Chart(
                chart = lineChart(
                    lines = listOf(
                        lineSpec(lineColor = FitnessColour, lineBackgroundShader = null),
                        lineSpec(lineColor = FatigueColour, lineBackgroundShader = null),
                    )
                ),
                chartModelProducer = producer,
                startAxis = rememberStartAxis(valueFormatter = wholeNumbers),
                bottomAxis = rememberBottomAxis(
                    valueFormatter = dateLabels,
                    itemPlacer = threeLabelPlacer(days.size),
                    // No vertical guidelines. Vico draws one per day rather than one per label, and
                    // over a year that is 366 dashed lines — a hatch the curves had to be read
                    // through. The horizontal ones off the value axis are the ones that help.
                    guideline = null,
                ),
                // The whole range at once, which is the only thing the range chips can mean. Left
                // to itself Vico gives every day a fixed width and lets the chart scroll: a year
                // and three months then looked identical — both drew their first ten days and hid
                // the rest off the right-hand edge. Scrolling off, Vico fits what it is given.
                chartScrollSpec = rememberChartScrollSpec(isScrollEnabled = false),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The weeks of training, one bar each, in whichever measure the toggle is on (#64).
 *
 * One chart with a toggle above it rather than three charts stacked: the weeks and their order do
 * not change between measures, only the heights, and three charts would ask the runner to find the
 * same week three times.
 */
@Composable
private fun WeeklyVolume(
    weeks: List<TrainingWeek>,
    measure: WeeklyMeasure,
    onMeasureChosen: (WeeklyMeasure) -> Unit,
) {
    Text(
        text = "Weekly volume",
        style = MaterialTheme.typography.titleMedium,
    )
    ChipPicker(
        options = WeeklyMeasure.entries,
        selected = measure,
        labelOf = { it.label },
        onChosen = onMeasureChosen,
    )
    WeeklyVolumeChart(weeks = weeks, measure = measure)
}

@Composable
private fun WeeklyVolumeChart(weeks: List<TrainingWeek>, measure: WeeklyMeasure) {
    // Built with its data in hand, and rebuilt when the weeks or the measure change — the same rule
    // the curve chart above records: a Vico chart measured against an empty model throws.
    val producer = remember(weeks, measure) {
        ChartEntryModelProducer(
            weeks.mapIndexed { index, week ->
                entryOf(index.toFloat(), measure.amountOf(week).toFloat())
            }
        )
    }

    val firstWeek = weeks.first().startingOn
    val weekLabels = AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
        firstWeek.plusWeeks(value.toLong()).format(AxisDateFormat)
    }
    val amounts = AxisValueFormatter<AxisPosition.Vertical.Start> { value, _ ->
        amountText(measure, value.toDouble())
    }

    val total = weeks.sumOf { measure.amountOf(it) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics {
                contentDescription = "Weekly ${measure.label} from " +
                    "${firstWeek.format(AxisDateFormat)} to " +
                    "${weeks.last().startingOn.format(AxisDateFormat)}, " +
                    "${totalText(measure, total)} in total"
            }
    ) {
        ProvideChartStyle(m3ChartStyle()) {
            Chart(
                // Bars sit on zero without being asked to: Vico's column chart takes the value
                // axis's floor as `minY.coerceAtMost(0f)`, so a block of 38-to-42 km weeks is drawn
                // as the near-identical weeks it was rather than as a tenfold climb.
                chart = columnChart(
                    columns = listOf(
                        lineComponent(
                            color = VolumeColour,
                            // Vico scales this by the zoom it fits the chart at, so one thickness
                            // holds for thirteen weeks and for fifty-two.
                            thickness = 8.dp,
                            shape = Shapes.roundedCornerShape(topLeftPercent = 20, topRightPercent = 20),
                        )
                    )
                ),
                chartModelProducer = producer,
                startAxis = rememberStartAxis(valueFormatter = amounts),
                bottomAxis = rememberBottomAxis(
                    valueFormatter = weekLabels,
                    itemPlacer = threeLabelPlacer(weeks.size),
                    guideline = null,
                ),
                // The whole range at once, as above: the range chips are the only thing that decides
                // how much is shown.
                chartScrollSpec = rememberChartScrollSpec(isScrollEnabled = false),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    Text(
        text = "Each bar is one week, Monday to Sunday",
        style = MaterialTheme.typography.labelMedium,
    )
}

/**
 * A weekly total as it is written down the axis.
 *
 * A tenth for distance and hours, because a week is 42.4 km and 3.5 hours and rounding either to a
 * whole would throw away a real difference between two weeks. Nothing after the point for an Effort
 * Score, which is a whole number to begin with, and none for a round distance either — an axis of
 * "10.0, 20.0, 30.0" is three decimals standing for nothing.
 *
 * Whether there is a tenth to show is decided on the number and not on the string it formats to. A
 * phone set to a comma-decimal locale writes 20.0 as "20,0", and a rule that struck off a trailing
 * ".0" would leave every one of those labels carrying a decimal the others had lost.
 */
private fun amountText(measure: WeeklyMeasure, amount: Double): String {
    if (measure == WeeklyMeasure.EFFORT_SCORE) return amount.roundToInt().toString()
    val tenths = (amount * 10).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "%.1f".format(amount)
}

/** A total with the unit it is counted in, for the chart's spoken description. */
private fun totalText(measure: WeeklyMeasure, total: Double): String {
    val amount = amountText(measure, total)
    // An Effort Score has no unit — it is a number and nothing per anything — so there is nothing
    // to put after it, and a blank one would be read out as a stumble.
    return if (measure.unit.isEmpty()) amount else "$amount ${measure.unit}"
}

@Composable
private fun ChartKey() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("— Fitness", color = FitnessColour, style = MaterialTheme.typography.labelLarge)
        Text("— Fatigue", color = FatigueColour, style = MaterialTheme.typography.labelLarge)
    }
}

@Preview(showBackground = true)
@Composable
private fun ProgressScreenPreview() {
    val start = LocalDate.of(2026, 5, 1)
    val days = (0 until 90).map { index ->
        ProgressDay(
            date = start.plusDays(index.toLong()),
            fitness = 20.0 + index * 0.4,
            fatigue = 25.0 + 10 * kotlin.math.sin(index / 4.0),
            form = 20.0 + index * 0.4 - (25.0 + 10 * kotlin.math.sin(index / 4.0)),
        )
    }
    val weeks = (0 until 13).map { index ->
        TrainingWeek(
            startingOn = start.plusWeeks(index.toLong()),
            distanceKm = 20.0 + (index % 4) * 8.0,
            timeSeconds = 7_200L + (index % 4) * 1_800L,
            effortScore = 200 + (index % 4) * 60,
        )
    }
    RunningAppTheme {
        ProgressScreen(
            state = ProgressUiState(
                range = ProgressRange.THREE_MONTHS,
                today = days.last(),
                curve = days,
                measure = WeeklyMeasure.DISTANCE,
                weeks = weeks,
            ),
            onRangeChosen = {},
            onMeasureChosen = {},
            onBack = {},
        )
    }
}
