package com.example.runningapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
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
import com.example.runningapp.training.formVerdictOf
import com.example.runningapp.ui.theme.RunningAppTheme
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.line.lineSpec
import com.patrykandpatrick.vico.compose.m3.style.m3ChartStyle
import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
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

private val AxisDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/**
 * How the runner's training is going (#63): today's Fitness, Fatigue and Form in words and numbers,
 * and the two curves that got them there.
 *
 * The numbers come first and the chart second on purpose — the runner should be able to get the
 * verdict without reading a chart at all (spec #60, story 7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    state: ProgressUiState,
    onRangeChosen: (ProgressRange) -> Unit,
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
            val today = state.today
            if (today == null) {
                Text(
                    "No scored runs yet. Once a run with heart rate is recorded, your Fitness, " +
                        "Fatigue and Form appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            TodayCard(today)
            RangePicker(selected = state.range, onRangeChosen = onRangeChosen)
            FitnessFatigueChart(days = state.curve)
            ChartKey()
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProgressRange.entries.forEach { range ->
            FilterChip(
                selected = range == selected,
                onClick = { onRangeChosen(range) },
                label = { Text(range.label) },
            )
        }
    }
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

    val producer = remember { ChartEntryModelProducer() }
    LaunchedEffect(days) {
        producer.setEntries(
            days.mapIndexed { index, day -> entryOf(index.toFloat(), day.fitness.toFloat()) },
            days.mapIndexed { index, day -> entryOf(index.toFloat(), day.fatigue.toFloat()) },
        )
    }

    val firstDay = days.first().date
    val dateLabels = AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
        firstDay.plusDays(value.toLong()).format(AxisDateFormat)
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
                startAxis = rememberStartAxis(),
                bottomAxis = rememberBottomAxis(valueFormatter = dateLabels),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
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
    RunningAppTheme {
        ProgressScreen(
            state = ProgressUiState(
                range = ProgressRange.THREE_MONTHS,
                today = days.last(),
                curve = days,
            ),
            onRangeChosen = {},
            onBack = {},
        )
    }
}
