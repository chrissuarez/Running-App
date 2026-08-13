package com.example.runningapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.example.runningapp.HrProfile
import com.example.runningapp.HrZone
import com.example.runningapp.analysis.RunAnalysis
import com.example.runningapp.analysis.runHeadline
import com.example.runningapp.ui.theme.RunningUiTokens
import com.example.runningapp.data.Achievement
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunWalkIntervalStat
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.isFinished
import com.example.runningapp.data.bandedOnHrProfile
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.data.StatedBestEffort
import com.example.runningapp.data.isTreadmill
import com.example.runningapp.data.TrackPoint
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
    // The route, gated for accuracy the way the map gates it. Empty for a treadmill run, and until
    // it has loaded — the splits table and the elevation line are simply absent until then, which is
    // the same thing they show for a run that never recorded a route.
    trackPoints: List<TrackPoint> = emptyList(),
    // What this run took a medal for (#49). Empty for the runs that won nothing, which is most of
    // them, and for every run finished before the record book existed — #50 scores those.
    achievements: List<Achievement> = emptyList(),
    // What to colour the route's zones by (#47) for a run carrying no Reserve of its own — the
    // heart rates history is banded against. A run that carries one is coloured by that (#228), so
    // the route and the zone bars further down the same page can never disagree. Null where neither
    // is known, and the route is then drawn in one colour.
    fallbackHrProfile: HrProfile? = null,
    onDeleteSession: (Long) -> Unit,
    onBack: () -> Unit,
    // How far a treadmill Run went, told to the app (#231). Null means the number would be refused
    // anyway — an outdoor Run, or a Run still being recorded — and the card is then read-only.
    onStateDistance: ((Long, Double?) -> Unit)? = null,
    // How the Run felt, and whether the runner walked it — said or changed after the fact (#80,
    // #275). Null leaves the card read-only, which is what a Run still being recorded gets — the
    // repository refuses one anyway.
    onSaveFeelFeedback: ((Long, Int?, String?, Boolean) -> Unit)? = null,
    // What a treadmill Run has been told it holds, and the way to tell it (#282). Empty and null on
    // every Run that could not hold one, which is what keeps the card off an outdoor page.
    statedBestEfforts: List<StatedBestEffort> = emptyList(),
    onStateBestEffort: ((Long, RecordType, Int?) -> Unit)? = null,
    // A run with no recorded GPS track — a treadmill run, or history from before #37 — has nothing to
    // put in a GPX file, so Share is left off the bar entirely rather than offered greyed out (#84).
    canShareGpx: Boolean = false,
    onShareGpx: (Long) -> Unit = {},
    shareFailed: Boolean = false,
    onShareFailureShown: () -> Unit = {}
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // Kept across a rotation or a process death, so a runner who turned the phone sideways to look
    // at their route is still looking at it afterwards.
    var showFullScreenMap by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(shareFailed) {
        if (shareFailed) {
            snackbarHostState.showSnackbar("Couldn't create the GPX file for this run")
            onShareFailureShown()
        }
    }

    // Worked out once per set of recordings rather than on every recomposition: a long run is
    // thousands of samples and thousands of fixes, and the runner's finger on the scrubber
    // recomposes this screen many times a second.
    val analysis = remember(session, samples, trackPoints, fallbackHrProfile) {
        session?.let { RunAnalysis.of(it, samples, trackPoints, it.bandedOnHrProfile() ?: fallbackHrProfile) }
    }

    // What the map and the chart both read: where the runner's finger is on the chart, in metres
    // along the Run (#48).
    val scrubber = rememberChartScrubber()

    // The full-screen map replaces the page rather than being a destination of its own: the route is
    // already worked out here, and closing it puts the runner back exactly where they were (#47).
    val trackMap = analysis?.trackMap
    if (session != null && showFullScreenMap && trackMap != null) {
        RunTrackMapFullScreen(
            trackMap = trackMap,
            title = runHeadline(session),
            onBack = { showFullScreenMap = false }
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                // The Run, not the screen (#44). A page about one outing is titled the way the
                // runner would name it — "Morning Run" — rather than "Session Summary", which named
                // the report and not the run it reports on. The date stays in the summary card
                // immediately below rather than being said twice.
                title = { Text(if (session == null) "Run" else runHeadline(session)) },
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
        // Both or neither: the analysis is worked out from the run, so it is only missing while the
        // run itself is still being read.
        if (session == null || analysis == null) {
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
                // The page's order (#43): route map, summary, achievements, splits, chart, then the
                // coaching cards the app already had. A treadmill run, and any run whose recording
                // holds no route, simply has no map — the page starts at the summary instead.
                //
                // Where there is a map the chart comes straight after it, ahead of the summary,
                // rather than at the bottom (#48). The two are one instrument: a finger on the chart
                // puts a dot on the route. Measured on the phone with the order #43 asked for, the
                // map is two screens above the chart while the finger is on it — a link the runner
                // would never once see. A run with no map has nothing to sit under and nothing to
                // link to, so its chart stays where it was.
                if (trackMap != null) {
                    RunTrackMapCard(
                        trackMap = trackMap,
                        onOpenFullScreen = { showFullScreenMap = true },
                        scrubber = scrubber,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    RunChartSection(analysis = analysis, scrubber = scrubber)
                    Spacer(modifier = Modifier.height(24.dp))
                }

                SummaryStats(
                    session = session,
                    elevationGainMeters = analysis.elevationGainMeters,
                    // Asked of the Run rather than of the screen: only a finished treadmill Run can
                    // be told a distance, and the repository refuses anything else regardless.
                    onStateDistance = onStateDistance
                        ?.takeIf { session.isFinished() && session.isTreadmill() }
                        ?.let { state -> { km: Double? -> state(session.id, km) } },
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Directly under the numbers the app measured, because this is the same run
                // described the only way the app cannot measure. Shown even when there is nothing
                // there yet: the sheet at the finish is skippable and easy to miss, and a page that
                // showed the card only once it had something to show would give a runner who
                // skipped it no way back in at all (#80).
                FeelFeedbackCard(
                    effort = session.perceivedEffort,
                    note = session.sessionNote,
                    isWalk = session.isWalk,
                    onSave = onSaveFeelFeedback
                        ?.takeIf { session.isFinished() }
                        ?.let { save ->
                            { effort: Int?, note: String?, isWalk: Boolean ->
                                save(session.id, effort, note, isWalk)
                            }
                        }
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Above the medals rather than below them, because this is where a treadmill Run's
                // medals at the five distances come from: the claim is made here and the book
                // answers underneath it (#282). Offered on the same terms as a Stated Distance —
                // only a finished treadmill Run, which is all the repository will accept anyway.
                //
                // Asked of the Run and not only of the screen: a Run stated at 600 metres holds no
                // record distance, and a heading over a card that draws nothing would be a section
                // that exists to say no.
                val statingEfforts = onStateBestEffort
                    ?.takeIf { session.isFinished() && session.isTreadmill() }
                    ?.takeIf {
                        recordDistancesWithin(session.distanceKm).isNotEmpty() ||
                            statedBestEfforts.isNotEmpty()
                    }
                if (statingEfforts != null) {
                    Text("Best efforts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    StatedBestEffortsCard(
                        stated = statedBestEfforts,
                        runDurationSeconds = session.durationSeconds,
                        statedDistanceKm = session.distanceKm,
                        onState = { type, seconds -> statingEfforts(session.id, type, seconds) },
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }

                if (achievements.isNotEmpty()) {
                    Text("Achievements", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    AchievementsCard(achievements = achievements)
                    Spacer(modifier = Modifier.height(24.dp))
                }

                if (analysis.splits.isNotEmpty()) {
                    Text("Splits", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    SplitsTable(splits = analysis.splits)
                    Spacer(modifier = Modifier.height(24.dp))
                }

                if (trackMap == null) {
                    RunChartSection(analysis = analysis, scrubber = scrubber)
                    Spacer(modifier = Modifier.height(24.dp))
                }

                Text("Heart Rate Zones", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                ZoneBarChart(session)
                Spacer(modifier = Modifier.height(24.dp))

                // Interval stats exist only for structured run/walk workouts, so their presence is
                // the signal to show the interval cards (#107 retired the session-type gate).
                if (intervalStats.isNotEmpty()) {
                    RunWalkIntervalSummaryCard(intervalStats = intervalStats)
                    Spacer(modifier = Modifier.height(24.dp))
                    RunWalkIntervalRawDataCard(intervalStats = intervalStats)
                    Spacer(modifier = Modifier.height(24.dp))
                }
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

/**
 * The Run's chart and the heading that says what its bottom axis means.
 *
 * An outdoor run is read against the ground it covered and a treadmill run against its own clock, so
 * the two get different charts and different headings (#46) — the heading has to say what the bottom
 * axis means or the chart is a guess. Its own function because the page shows it in one of two
 * places depending on whether the Run has a route to sit under.
 */
@Composable
private fun RunChartSection(analysis: RunAnalysis, scrubber: ChartScrubber) {
    val combined = analysis.distanceChart
    if (combined != null) {
        Text(
            headingFor(combined),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        RunCombinedChart(chart = combined, scrubber = scrubber)
    } else {
        Text("Heart Rate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        RunAnalysisChart(chart = analysis.chart)
    }
}

/**
 * The Run's numbers.
 *
 * [onStateDistance] is how far the Run went, told to the app — offered only where a distance can be
 * stated at all, which is a finished treadmill Run and nothing else (#231, ADR 0008). Null leaves
 * the card read-only, which is what an outdoor Run gets: its distance is measured, and one whose GPS
 * recorded nothing is deliberately not rescued this way.
 */
@Composable
fun SummaryStats(
    session: RunnerSession,
    elevationGainMeters: Double? = null,
    onStateDistance: ((Double?) -> Unit)? = null,
) {
    val sdf = SimpleDateFormat("EEEE, MMM d, yyyy 'at' HH:mm", Locale.getDefault())
    val dateStr = sdf.format(Date(session.startTime))
    var showDistanceDialog by remember { mutableStateOf(false) }

    if (showDistanceDialog && onStateDistance != null) {
        StatedDistanceDialog(
            distanceKm = session.distanceKm,
            onDismiss = { showDistanceDialog = false },
            onState = { km ->
                showDistanceDialog = false
                onStateDistance(km)
            }
        )
    }

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

            // What the Run cost (#61). Only for a Run that has a score: a Run recorded without a
            // Strap, or one from before v21 that the backfill has not reached, shows nothing rather
            // than a zero — a zero here means an hour spent below Zone 1, which is a real answer and
            // must not be confused with an absent one.
            //
            // "Effort Score" in full because the card below says "Effort" for how the Run *felt*
            // (#80), and the two numbers are unrelated by design — one is measured off the runner's
            // heart, the other is the runner's own word for the same hour.
            val effortScore = session.effortScore
            if (effortScore != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatLarge(label = "Effort Score", value = "$effortScore")
                }
            }

            // On every Run, not only an outdoor one (#231). A treadmill Run can be told how far it
            // went, and a Run with no distance says so with a dash rather than with "0.00 km",
            // which was always a lie about a treadmill Run and is the same lie about an outdoor Run
            // the GPS lost.
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatLarge(
                    label = "Distance",
                    value = if (session.distanceKm > 0.0) "%.2f km".format(session.distanceKm) else "--"
                )
                // Derived, not read from the stored column (#163). The label says which clock
                // the number is over, because it is not always the same one: a measured run is
                // paced over its moving time, and a treadmill run or one with no usable track
                // falls back to its duration. #163 asked for that to be said rather than left
                // to be inferred from whether the Moving row above happens to be there.
                val paceLabel = session.averagePaceText
                    .let { if (session.averagePace > 0) "$it min/km" else it }
                StatLarge(
                    label = if (session.movingTimeSeconds != null) "Avg Pace (moving)" else "Avg Pace",
                    value = paceLabel
                )
            }

            // The way in for a number the app cannot measure. On the Run's own page rather than only
            // on the sheet at the finish, because a Stated Distance has to be correctable: it
            // reaches the volume, the coach and the record book, so a mistyped one is not cosmetic
            // (ADR 0008).
            if (onStateDistance != null) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { showDistanceDialog = true },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(if (session.distanceKm > 0.0) "Correct the distance" else "Add the distance")
                }
            }

            if (session.runMode == "outdoor") {
                // Only when the run recorded a height to climb (#45). A run whose track is
                // backfilled breadcrumbs carries a position and nothing else, and a confident
                // "0 m" would be a claim about the ground rather than about the recording.
                if (elevationGainMeters != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatLarge(label = "Elevation Gain", value = "${elevationGainMeters.roundToInt()} m")
                    }
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

/**
 * How the Run felt, in the runner's own words — and the way in to say it, or to take it back (#80).
 *
 * [onSave] null is a card that only reports: a Run still being recorded cannot be described yet.
 * With nothing said and no way in there is nothing to draw, so the card stands down entirely rather
 * than showing an empty heading.
 */
@Composable
private fun FeelFeedbackCard(
    effort: Int?,
    note: String?,
    isWalk: Boolean,
    onSave: ((Int?, String?, Boolean) -> Unit)?,
) {
    var showDialog by remember { mutableStateOf(false) }
    val noteText = feelNoteOf(note)
    if (effort == null && noteText == null && !isWalk && onSave == null) return

    if (showDialog && onSave != null) {
        FeelFeedbackDialog(
            effort = effort,
            note = noteText,
            isWalk = isWalk,
            onDismiss = { showDialog = false },
            onSave = { chosenEffort, typedNote, walked ->
                showDialog = false
                onSave(chosenEffort, typedNote, walked)
            }
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
            Text(
                text = "How it felt",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Above the effort, because it is the bigger fact about the Run: everything below is
            // the runner's word on how it went, and this is their word on what it was (#275).
            if (isWalk) {
                WalkMarker()
                Spacer(modifier = Modifier.height(12.dp))
            }

            val effortText = feelEffortText(effort)
            if (effortText != null) {
                StatLarge(label = "Effort", value = effortText)
            } else {
                Text(
                    text = "No effort rated",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (noteText != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = noteText, style = MaterialTheme.typography.bodyMedium)
            }

            if (onSave != null) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { showDialog = true },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(feelEditLabel(effort = effort, note = noteText, isWalk = isWalk))
                }
            }
        }
    }
}

@Composable
private fun RunWalkIntervalSummaryCard(intervalStats: List<RunWalkIntervalStat>) {
    val metrics = remember(intervalStats) {
        computeRunWalkIntervalAnalytics(intervalStats)
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
            SummaryMetricRow(
                "Intervals with no trigger",
                "${metrics.intervalsWithNoTrigger} of ${metrics.totalIntervals}"
            )
            SummaryMetricRow(
                "Average time to first trigger",
                metrics.avgSecondsBeforeTrigger?.let { formatMinutesSeconds(it) } ?: "--"
            )
            SummaryMetricRow(
                "Longest interval with no trigger",
                metrics.longestIntervalWithNoTriggerSeconds?.let { formatMinutesSeconds(it) } ?: "--"
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
        // The label takes the leftover width and wraps; the value keeps whatever it needs. Without
        // this a long label ("Average time before heart rate went above target") squeezes the number
        // it belongs to off the row on a narrow phone.
        Text(
            text = label,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
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
    Column {
        Text(
            text = "Interval ${stat.intervalIndex + 1}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        SummaryMetricRow("Planned run duration", formatMinutesSeconds(stat.plannedDurationSeconds))
        SummaryMetricRow("Actual run before trigger", formatMinutesSeconds(stat.actualRunningDurationBeforeHrTriggerSeconds))
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
    }
}

@Composable
fun StatLarge(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
