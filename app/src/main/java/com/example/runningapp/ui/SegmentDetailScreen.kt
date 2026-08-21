package com.example.runningapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.example.runningapp.analysis.MapFix
import com.example.runningapp.data.Segment
import com.example.runningapp.data.SegmentEffortRow
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.ui.theme.RunningUiTokens
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec
import com.patrykandpatrick.vico.compose.m3.style.m3ChartStyle
import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.entryOf

/**
 * How tall the map is.
 *
 * Fixed rather than taking whatever room the words leave, because there is no longer a fixed amount
 * of room to take: under it is a list of every effort ever run here, which is two rows on a new
 * Segment and fifty on an old one. Fixed also means it does not shrink to nothing as that list grows
 * — a Segment is a piece of ground, and the page has to show the ground.
 */
private val MapHeight = 220.dp

/**
 * One Segment's own page: its ground, its PR, and every time the runner has been over it (#69, #70).
 *
 * The list is the point of the page. A Segment is a place the runner measures themselves against,
 * and one time means nothing without the ones before it — "4:32" is a different fact depending on
 * whether it is the best of two attempts or of fifty, which is why the count sits beside it.
 *
 * The map is no longer interactive, and the page scrolls instead. It was the other way round while
 * there was nothing under the map (#69): a pannable map inside a scrolling column steals the drag
 * that was meant to scroll the page, and between panning a map and reaching the efforts, reaching
 * the efforts is what the runner came for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentDetailScreen(
    segment: Segment?,
    efforts: List<SegmentEffortRow>,
    onRename: (Segment, String) -> Unit,
    onDelete: (Segment) -> Unit,
    onOpenRun: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(segment?.name ?: "Segment") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { renaming = true }, enabled = segment != null) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename segment")
                    }
                    IconButton(onClick = { deleting = true }, enabled = segment != null) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete segment")
                    }
                },
            )
        },
    ) { padding ->
        if (segment == null) {
            // The row is watched, so this is both "still loading" and "just deleted". Either way
            // there is nothing to draw, and the caller pops the page when the row goes.
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val ground = remember(segment.polyline) {
            RoutePolyline.decode(segment.polyline).map { MapFix(it.latitude, it.longitude) }
        }
        val shown = remember(efforts, segment.distanceMeters) {
            segmentEffortsUi(efforts, segment.distanceMeters)
        }
        val record = remember(shown) { segmentRecordOf(shown) }
        val ranked = remember(shown) { segmentTopEfforts(shown) }
        val trend = remember(shown) { segmentTrendPoints(shown) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(RunningUiTokens.PagePadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(MapHeight)
                    ) {
                        SegmentMapSurface(
                            segment = ground,
                            runBehind = emptyList(),
                            interactive = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            item {
                Text(
                    text = segmentDistanceLabel(segment.distanceMeters),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                segmentSourceLabel(segment)?.let { source ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = source,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                if (record == null) {
                    Text(
                        text = NO_SEGMENT_EFFORTS_MESSAGE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Personal record",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = record.timeLabel,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = segmentEffortCountLabel(shown.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (trend.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
                            Text(
                                text = SEGMENT_TREND_TITLE,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = SEGMENT_TREND_SUBTITLE,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            SegmentTrendChart(points = trend)
                        }
                    }
                }
            }

            if (ranked.isNotEmpty()) {
                item {
                    Text(
                        text = segmentTopTitle(shown.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(ranked, key = { "ranked-" + it.effort.effortId }) { placed ->
                SegmentRankedEffortRow(placed = placed, onOpen = { onOpenRun(placed.effort.sessionId) })
            }

            // Past ten, the ranked list is no longer the whole of the runner's history at this
            // place, so the whole of it goes back underneath. A page that stopped at ten would take
            // runs off a runner for having done nothing but keep running — and the run they are
            // most likely looking for is the one they did on Sunday, which is why this half is
            // newest first.
            if (shown.size > SEGMENT_TOP_COUNT) {
                item {
                    Text(
                        text = SEGMENT_ALL_EFFORTS_TITLE,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                items(shown, key = { "all-" + it.effortId }) { effort ->
                    SegmentEffortRowUi(effort = effort, onOpen = { onOpenRun(effort.sessionId) })
                }
            }
        }
    }

    if (renaming && segment != null) {
        RenameSegmentDialog(
            segment = segment,
            onDismiss = { renaming = false },
            onRename = { name ->
                onRename(segment, name)
                renaming = false
            },
        )
    }

    if (deleting && segment != null) {
        DeleteSegmentDialog(
            segment = segment,
            onDismiss = { deleting = false },
            onDelete = {
                deleting = false
                onDelete(segment)
            },
        )
    }
}

/**
 * One effort in the ranked list: where it placed, when it was run, how quick it was, and the time.
 *
 * The whole row is a door to the Run it was part of. A time on a hill is not the whole story of the
 * morning the runner ran it, and the page they would go looking for it on is the Run's own.
 *
 * The place is a medal disc in the top three and the bare number below it — the same discs the
 * record book and a Run's own page hand out (#71), because a place is a place. The time is the
 * column on its own at the end, because it is the number the runner is scanning the list for; the
 * date and the pace read as one line under each other so the row survives a narrow phone at a large
 * text size (#63).
 */
@Composable
private fun SegmentRankedEffortRow(placed: SegmentRankedEffortUi, onOpen: () -> Unit) {
    val effort = placed.effort
    val spokenPlace = placed.medal?.let { "${it.face.spoken}, " } ?: "Number ${placed.place}, "
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RunningUiTokens.MinTouchTarget)
            .clickable(onClick = onOpen)
            .semantics {
                contentDescription =
                    "$spokenPlace${effort.dateLabel}, ${effort.timeLabel}, ${effort.paceLabel}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (placed.medal != null) {
            MedalDisc(placed.medal)
        } else {
            // The same width the discs take, so the dates line up down the list instead of
            // stepping in at fourth place.
            Box(
                modifier = Modifier
                    .size(RunningUiTokens.MedalDiscSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${placed.place}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = effort.dateLabel, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = effort.paceLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = effort.timeLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * One effort in the newest-first list: when it was run, how quick it was, and the time.
 *
 * Keeps the PR marker, because this half of the page carries no medals: a runner scrolling their
 * recent efforts should still be able to see which of them is the one to beat.
 */
@Composable
private fun SegmentEffortRowUi(effort: SegmentEffortUi, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RunningUiTokens.MinTouchTarget)
            .clickable(onClick = onOpen)
            .semantics {
                contentDescription = (if (effort.isRecord) "Personal record, " else "") +
                    "${effort.dateLabel}, ${effort.timeLabel}, ${effort.paceLabel}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = effort.dateLabel, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = effort.paceLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (effort.isRecord) {
            Text(
                text = "PR",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(
            text = effort.timeLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * The times run at a Segment, over the calendar they were run on (#72).
 *
 * Drawn against the day rather than against the effort number, so the gaps between attempts are the
 * gaps that really happened — see [segmentTrendPoints]. Styled as the Progress screen's charts are:
 * the Material palette through [m3ChartStyle], three dates along the bottom, no vertical guidelines,
 * and no scrolling, so the whole of the runner's history at this place is on screen at once.
 */
@Composable
private fun SegmentTrendChart(points: List<SegmentTrendPoint>) {
    // Handed to the producer as it is built rather than pushed into an empty one afterwards: a Vico
    // chart measured against an empty model throws, and an effect runs a frame too late (#63).
    val producer = remember(points) {
        ChartEntryModelProducer(
            points.map { entryOf(it.dayOffset.toFloat(), it.seconds.toFloat()) }
        )
    }

    val firstDay = points.first().date
    val dateLabels = AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
        firstDay.plusDays(value.toLong()).format(TrendDateFormat)
    }
    val timeLabels = AxisValueFormatter<AxisPosition.Vertical.Start> { value, _ ->
        segmentTrendTimeLabel(value)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TrendChartHeight)
            .semantics { contentDescription = segmentTrendDescription(points).orEmpty() }
    ) {
        ProvideChartStyle(m3ChartStyle()) {
            Chart(
                chart = lineChart(),
                chartModelProducer = producer,
                startAxis = rememberStartAxis(valueFormatter = timeLabels),
                bottomAxis = rememberBottomAxis(
                    valueFormatter = dateLabels,
                    itemPlacer = threeLabelPlacer(segmentTrendAxisTicks(points)),
                    guideline = null,
                ),
                chartScrollSpec = rememberChartScrollSpec(isScrollEnabled = false),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** How tall the trend chart is — the height the Progress screen's own line chart stands at. */
private val TrendChartHeight = 240.dp

private val TrendDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yy", Locale.UK)
