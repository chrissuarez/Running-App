package com.example.runningapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt
import com.example.runningapp.HrZone
import com.example.runningapp.ui.theme.RunningUiTokens
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.IntervalCompletionBand
import com.example.runningapp.data.RunWalkIntervalStat
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.classifyIntervalCompletionBand
import com.example.runningapp.data.computeRunWalkIntervalAnalytics
import com.example.runningapp.data.averagePace
import com.example.runningapp.data.averagePaceText
import com.example.runningapp.data.inTargetZoneSeconds
import com.example.runningapp.data.secondsInZone
import com.example.runningapp.ui.workout.zoneChartColor
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    session: RunnerSession?,
    samples: List<HrSample>,
    intervalStats: List<RunWalkIntervalStat>,
    onDeleteSession: (Long) -> Unit,
    onBack: () -> Unit,
    // A run with no recorded GPS track — a treadmill run, or history from before #37 — has nothing to
    // put in a GPX file, so Share is left off the bar entirely rather than offered greyed out (#84).
    canShareGpx: Boolean = false,
    onShareGpx: (Long) -> Unit = {},
    shareFailed: Boolean = false,
    onShareFailureShown: () -> Unit = {}
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(shareFailed) {
        if (shareFailed) {
            snackbarHostState.showSnackbar("Couldn't create the GPX file for this run")
            onShareFailureShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Session Summary") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (session != null && canShareGpx) {
                        IconButton(onClick = { onShareGpx(session.id) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share run as GPX")
                        }
                    }
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        enabled = session != null
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete run")
                    }
                }
            )
        }
    ) { padding ->
        if (session == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(RunningUiTokens.PagePadding)
            ) {
                SummaryStats(session)
                Spacer(modifier = Modifier.height(24.dp))

                // Interval stats exist only for structured run/walk workouts, so their presence is
                // the signal to show the interval cards (#107 retired the session-type gate).
                if (intervalStats.isNotEmpty()) {
                    RunWalkIntervalSummaryCard(intervalStats = intervalStats)
                    Spacer(modifier = Modifier.height(24.dp))
                    RunWalkIntervalRawDataCard(intervalStats = intervalStats)
                    Spacer(modifier = Modifier.height(24.dp))
                }
                
                Text("Heart Rate Zones", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                ZoneBarChart(session)
                Spacer(modifier = Modifier.height(24.dp))

                Text("Heart Rate Chart", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                HrChart(samples = samples, modifier = Modifier.fillMaxWidth().height(200.dp))
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showDeleteConfirm && session != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete run?") },
            text = { Text("Are you sure you want to delete this run?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteSession(session.id)
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SummaryStats(session: RunnerSession) {
    val sdf = SimpleDateFormat("EEEE, MMM d, yyyy 'at' HH:mm", Locale.getDefault())
    val dateStr = sdf.format(Date(session.startTime))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
            Text(text = dateStr, style = MaterialTheme.typography.bodySmall)
            if (session.weatherTempC != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatWeatherLine(session),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatLarge(label = "Duration", value = formatDurationLarge(session.durationSeconds))
                StatLarge(label = "Avg HR", value = "${session.avgBpm}")
            }

            // Moving time is what pace is measured over (#163), so it is shown rather than left to
            // be inferred from a pace that no longer divides the duration. Only for runs that have
            // one: a treadmill run has no track to compute it from.
            if (session.movingTimeSeconds != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatLarge(label = "Moving", value = formatDurationLarge(session.movingTimeSeconds))
                    // Never below zero: moving time is capped at the run's own clock when it is
                    // measured, and this is the last line of defence for a row stored before that.
                    val resting = (session.durationSeconds - session.movingTimeSeconds).coerceAtLeast(0)
                    StatLarge(label = "Resting", value = formatDurationLarge(resting))
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatLarge(label = "Max HR", value = "${session.maxBpm}")
                StatLarge(label = "In Target", value = formatDurationLarge(session.inTargetZoneSeconds))
            }

            if (session.runMode == "outdoor") {
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatLarge(label = "Distance", value = "%.2f km".format(session.distanceKm))
                    // Derived, not read from the stored column (#163).
                    val paceLabel = session.averagePaceText
                        .let { if (session.averagePace > 0) "$it min/km" else it }
                    StatLarge(label = "Avg Pace", value = paceLabel)
                }
            }

            if (session.isRunWalkMode || session.walkBreaksCount > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatLarge(
                        label = "Walk Breaks", 
                        value = "${session.walkBreaksCount}"
                    )
                    if (session.isRunWalkMode) {
                        StatLarge(
                            label = "Coach Mode", 
                            value = "Run/Walk"
                        )
                    }
                }
            }
        }
    }
}

private data class RunWalkIntervalSummaryMetrics(
    val totalIntervals: Int,
    val cleanPercent: Int,
    val avgTimeToTriggerSeconds: Int?,
    val longestCleanSeconds: Int?,
    val completionRatioPercent: Int,
    val severeBreakdownCount: Int,
    val severeBreakdownPercent: Int,
    val poorToleranceCount: Int,
    val poorTolerancePercent: Int,
    val strainedCompletionCount: Int,
    val strainedCompletionPercent: Int,
    val strongCompletionCount: Int,
    val strongCompletionPercent: Int
)

@Composable
private fun RunWalkIntervalSummaryCard(intervalStats: List<RunWalkIntervalStat>) {
    val metrics = remember(intervalStats) {
        computeRunWalkIntervalSummaryMetrics(intervalStats)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
            Text(
                text = "Run/Walk Interval Summary",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            SummaryMetricRow("Total run intervals", "${metrics.totalIntervals}")
            SummaryMetricRow("Average completion", "${metrics.completionRatioPercent}%")
            SummaryMetricRow("% intervals without HR trigger", "${metrics.cleanPercent}%")
            SummaryMetricRow(
                "Average time-to-trigger",
                metrics.avgTimeToTriggerSeconds?.let { formatMinutesSeconds(it) } ?: "--"
            )
            SummaryMetricRow(
                "Longest clean interval",
                metrics.longestCleanSeconds?.let { formatMinutesSeconds(it) } ?: "--"
            )
            SummaryMetricRow(
                "Severe breakdown (<30%)",
                "${metrics.severeBreakdownCount} (${metrics.severeBreakdownPercent}%)"
            )
            SummaryMetricRow(
                "Poor tolerance (30-59%)",
                "${metrics.poorToleranceCount} (${metrics.poorTolerancePercent}%)"
            )
            SummaryMetricRow(
                "Strained completion (60-89%)",
                "${metrics.strainedCompletionCount} (${metrics.strainedCompletionPercent}%)"
            )
            SummaryMetricRow(
                "Strong completion (>=90%)",
                "${metrics.strongCompletionCount} (${metrics.strongCompletionPercent}%)"
            )
        }
    }
}

@Composable
private fun SummaryMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun RunWalkIntervalRawDataCard(intervalStats: List<RunWalkIntervalStat>) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Raw Interval Data",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap to inspect the saved interval stats used by the summary and AI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse raw interval data" else "Expand raw interval data"
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                intervalStats.forEachIndexed { index, stat ->
                    RunWalkIntervalRawDataRow(stat = stat)
                    if (index < intervalStats.lastIndex) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RunWalkIntervalRawDataRow(stat: RunWalkIntervalStat) {
    val completionBand = classifyIntervalCompletionBand(stat)
    val completionPercent = if (stat.plannedDurationSeconds > 0) {
        ((stat.actualRunningDurationBeforeHrTriggerSeconds.toDouble() / stat.plannedDurationSeconds.toDouble()) * 100.0)
            .roundToInt()
            .coerceAtMost(100)
    } else {
        0
    }

    Column {
        Text(
            text = "Interval ${stat.intervalIndex + 1}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        SummaryMetricRow("Planned run duration", formatMinutesSeconds(stat.plannedDurationSeconds))
        SummaryMetricRow("Actual run before trigger", formatMinutesSeconds(stat.actualRunningDurationBeforeHrTriggerSeconds))
        SummaryMetricRow("Completion", "$completionPercent%")
        SummaryMetricRow(
            "First HR trigger",
            stat.timeIntoIntervalWhenHrExceededCapSeconds?.let { formatMinutesSeconds(it) } ?: "None"
        )
        SummaryMetricRow("HR trigger events", "${stat.hrTriggerEvents}")
        SummaryMetricRow(
            "Walking during run interval",
            formatMinutesSeconds(stat.totalTimeSpentWalkingDuringRunIntervalSeconds)
        )
        SummaryMetricRow(
            "Avg HR at trigger",
            stat.avgHrAtTriggerInInterval?.roundToInt()?.toString() ?: "--"
        )
        SummaryMetricRow(
            "Avg recovery after trigger",
            stat.avgRecoverySecondsAfterTriggerInInterval?.roundToInt()?.let { formatMinutesSeconds(it) } ?: "--"
        )
        SummaryMetricRow("Completion band", completionBand.label)
    }
}

private fun computeRunWalkIntervalSummaryMetrics(
    intervalStats: List<RunWalkIntervalStat>
): RunWalkIntervalSummaryMetrics {
    val totalIntervals = intervalStats.size
    if (totalIntervals == 0) {
        return RunWalkIntervalSummaryMetrics(
            totalIntervals = 0,
            cleanPercent = 0,
            avgTimeToTriggerSeconds = null,
            longestCleanSeconds = null,
            completionRatioPercent = 0,
            severeBreakdownCount = 0,
            severeBreakdownPercent = 0,
            poorToleranceCount = 0,
            poorTolerancePercent = 0,
            strainedCompletionCount = 0,
            strainedCompletionPercent = 0,
            strongCompletionCount = 0,
            strongCompletionPercent = 0
        )
    }
    val analytics = computeRunWalkIntervalAnalytics(intervalStats)

    return RunWalkIntervalSummaryMetrics(
        totalIntervals = analytics.totalIntervals,
        cleanPercent = analytics.cleanPercent,
        avgTimeToTriggerSeconds = analytics.avgTimeToTriggerSeconds,
        longestCleanSeconds = analytics.longestCleanSeconds,
        completionRatioPercent = analytics.completionRatioPercent,
        severeBreakdownCount = analytics.severeBreakdownCount,
        severeBreakdownPercent = analytics.severeBreakdownPercent,
        poorToleranceCount = analytics.poorToleranceCount,
        poorTolerancePercent = analytics.poorTolerancePercent,
        strainedCompletionCount = analytics.strainedCompletionCount,
        strainedCompletionPercent = analytics.strainedCompletionPercent,
        strongCompletionCount = analytics.strongCompletionCount,
        strongCompletionPercent = analytics.strongCompletionPercent
    )
}

private val IntervalCompletionBand.label: String
    get() = when (this) {
        IntervalCompletionBand.SEVERE_BREAKDOWN -> "Severe breakdown"
        IntervalCompletionBand.POOR_TOLERANCE -> "Poor tolerance"
        IntervalCompletionBand.STRAINED_COMPLETION -> "Strained completion"
        IntervalCompletionBand.STRONG_COMPLETION -> "Strong completion"
    }

@Composable
fun StatLarge(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HrChart(samples: List<HrSample>, modifier: Modifier = Modifier) {
    if (samples.isEmpty()) {
        Box(modifier = modifier.background(Color.Black.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) {
            Text("No chart data")
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

    val rawMax = samples.maxOf { it.rawBpm }.toFloat()
    val rawMin = samples.minOf { it.rawBpm }.toFloat()
    
    // Rounded range for cleaner labels
    val chartMin = (rawMin - 5f).coerceAtLeast(40f).let { (it / 10).toInt() * 10f }
    val chartMax = (rawMax + 5f).coerceAtMost(220f).let { ((it + 9) / 10).toInt() * 10f }
    val bpmRange = (chartMax - chartMin).coerceAtLeast(1f)

    val durationSeconds = samples.last().elapsedSeconds

    Canvas(modifier = modifier.background(Color.Black.copy(alpha = 0.05f)).padding(8.dp)) {
        val leftPadding = 32.dp.toPx() // Decreased slightly to fit text better
        val bottomPadding = 20.dp.toPx()
        val topPadding = 16.dp.toPx() // Improved top spacing
        val rightPadding = 16.dp.toPx() // Improved right spacing

        val chartWidth = size.width - leftPadding - rightPadding
        val chartHeight = size.height - bottomPadding - topPadding

        // 1. Draw Y-axis labels and horizontal grid lines (5 ticks)
        val yTicks = 5
        for (i in 0 until yTicks) {
            val fraction = i / (yTicks - 1).toFloat()
            val bpm = chartMin + (fraction * bpmRange)
            val y = topPadding + chartHeight - (fraction * chartHeight)
            
            // Grid line
            drawLine(
                color = Color.LightGray.copy(alpha = 0.5f),
                start = Offset(leftPadding, y),
                end = Offset(size.width - rightPadding, y),
                strokeWidth = 1.dp.toPx()
            )
            
            // Label
            val labelLayout = textMeasurer.measure(bpm.toInt().toString(), style = labelStyle)
            drawText(
                textLayoutResult = labelLayout,
                topLeft = Offset(leftPadding - labelLayout.size.width - 4.dp.toPx(), y - labelLayout.size.height / 2)
            )
        }

        // 2. Draw X-axis labels (3-4 ticks based on duration)
        val xTicks = if (durationSeconds < 120) 3 else 4
        for (i in 0 until xTicks) {
            val fraction = i / (xTicks - 1).toFloat()
            val seconds = (fraction * durationSeconds).toLong()
            val x = leftPadding + (fraction * chartWidth)
            
            val label = if (durationSeconds < 3600) {
                "%02d:%02d".format(seconds / 60, seconds % 60)
            } else {
                "%dh %dm".format(seconds / 3600, (seconds % 3600) / 60)
            }
            
            val labelLayout = textMeasurer.measure(label, style = labelStyle)
            
            // Adjust X position to keep text within bounds
            val textX = when (i) {
                0 -> x // Left aligned
                xTicks - 1 -> x - labelLayout.size.width // Right aligned
                else -> x - labelLayout.size.width / 2 // Center aligned
            }
            
            drawText(
                textLayoutResult = labelLayout,
                topLeft = Offset(textX, topPadding + chartHeight + 4.dp.toPx())
            )
        }

        // 3. Draw HR Path
        val path = Path()
        samples.forEachIndexed { index, sample ->
            val x = leftPadding + (sample.elapsedSeconds.toFloat() / durationSeconds.coerceAtLeast(1) * chartWidth)
            val y = topPadding + chartHeight - ((sample.rawBpm - chartMin) / bpmRange * chartHeight)
            
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        
        drawPath(
            path = path,
            color = Color.Red,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

private fun formatDurationLarge(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%dh %dm".format(h, m) else "%dm %ds".format(m, s)
}

private fun formatMinutesSeconds(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(mins, secs)
}

// https://open-meteo.com/en/docs (WMO Weather interpretation codes)
private val WMO_CONDITION_LABELS = mapOf(
    0 to "Clear sky",
    1 to "Mainly clear",
    2 to "Partly cloudy",
    3 to "Overcast",
    45 to "Fog",
    48 to "Fog",
    51 to "Light drizzle",
    53 to "Drizzle",
    55 to "Heavy drizzle",
    56 to "Freezing drizzle",
    57 to "Freezing drizzle",
    61 to "Light rain",
    63 to "Rain",
    65 to "Heavy rain",
    66 to "Freezing rain",
    67 to "Freezing rain",
    71 to "Light snow",
    73 to "Snow",
    75 to "Heavy snow",
    77 to "Snow grains",
    80 to "Light showers",
    81 to "Showers",
    82 to "Heavy showers",
    85 to "Snow showers",
    86 to "Snow showers",
    95 to "Thunderstorm",
    96 to "Thunderstorm with hail",
    99 to "Thunderstorm with hail"
)

private fun formatWeatherLine(session: RunnerSession): String {
    val tempC = session.weatherTempC ?: return ""
    val condition = session.weatherConditionCode?.let { WMO_CONDITION_LABELS[it] }
    return buildString {
        append("%.0f°C".format(tempC))
        session.weatherFeelsLikeC?.let { append(", feels %.0f°C".format(it)) }
        condition?.let { append(" · $it") }
        session.weatherHumidityPercent?.let { append(" · $it% humidity") }
        session.weatherWindSpeedKmh?.let { append(" · %.0f km/h wind".format(it)) }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewHrChart() {
    val samples = listOf(
        HrSample(1, 1, 0, 70, 70, "CONNECTED"),
        HrSample(2, 1, 30, 85, 80, "CONNECTED"),
        HrSample(3, 1, 60, 110, 100, "CONNECTED"),
        HrSample(4, 1, 90, 140, 130, "CONNECTED"),
        HrSample(5, 1, 120, 135, 135, "CONNECTED"),
        HrSample(6, 1, 150, 155, 150, "CONNECTED")
    )
    MaterialTheme {
        HrChart(
            samples = samples,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(16.dp)
        )
    }
}

/** One bar of [ZoneBarChart]. A null [color] means the bar sits outside the zone scale. */
private data class ZoneBar(val label: String, val seconds: Long, val color: Color?)

@Composable
fun ZoneBarChart(session: RunnerSession) {
    // No Data rides along as a bar but is not a zone: it is the run's unclassifiable seconds, so
    // it carries no place on the cool-to-hot scale and stays deliberately colourless.
    val bars = HrZone.entries.map { zone ->
        ZoneBar("Z${zone.number} ${zone.zoneName}", session.secondsInZone(zone), zoneChartColor(zone))
    } + ZoneBar("No Data", session.noDataSeconds, color = null)

    val maxSeconds = bars.maxOfOrNull { it.seconds } ?: 0L

    if (maxSeconds == 0L) {
        Text("No zone data available for this session.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        bars.forEach { bar ->
            val percentage = if (maxSeconds > 0) bar.seconds.toFloat() / maxSeconds else 0f
            val timeStr = formatDurationLarge(bar.seconds) // Reusing existing formatter

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = bar.label,
                    modifier = Modifier.width(96.dp), // Fits the longest zone name
                    style = MaterialTheme.typography.bodySmall, 
                    fontWeight = FontWeight.Bold
                )
                
                Box(modifier = Modifier.weight(1f).height(24.dp)) {
                    // Background track
                    Box(modifier = Modifier.fillMaxSize().background(Color.LightGray.copy(alpha = 0.3f)))
                    
                    // Filled bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(percentage)
                            .fillMaxHeight()
                            .background(bar.color ?: MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
                
                Text(
                    text = timeStr, 
                    modifier = Modifier.width(70.dp).padding(start = 8.dp), 
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
