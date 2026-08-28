package com.example.runningapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.RouteThumbnail
import com.example.runningapp.analysis.ThumbPoint
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.ranAt
import com.example.runningapp.run.RunMode
import com.example.runningapp.ui.theme.RunningAppTheme
import com.example.runningapp.ui.theme.RunningUiTokens
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    rows: List<HistoryRow>,
    selectedSessionIds: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onSessionClick: (Long) -> Unit,
    onBack: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val selectedCount = selectedSessionIds.size
    val selectionMode = selectedCount > 0

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("$selectedCount Selected") },
                    navigationIcon = {
                        IconButton(onClick = onClearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("History") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        }
    ) { padding ->
        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No sessions recorded yet.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(RunningUiTokens.PagePadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rows, key = { it.session.id }) { row ->
                    val sessionId = row.session.id
                    SessionItem(
                        row = row,
                        isSelected = selectedSessionIds.contains(sessionId),
                        onClick = {
                            if (selectionMode) {
                                onToggleSelection(sessionId)
                            } else {
                                onSessionClick(sessionId)
                            }
                        },
                        onLongClick = { onToggleSelection(sessionId) }
                    )
                }
            }
        }
    }

    if (showDeleteDialog && selectedCount > 0) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete selected runs?") },
            text = { Text("Delete $selectedCount selected runs?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteSelected()
                        onClearSelection()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionItem(
    row: HistoryRow,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val session = row.session
    // The date and time the runner set off under, read off the Run's own stamp (#304).
    val dateStr = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())
        .format(session.ranAt(ZoneId.systemDefault()))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RunningUiTokens.MinTouchTarget * 2)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The square is held open on every row, drawn or not: an outdoor Run's route is worked
            // out after the row is on screen, and a row that widened when its drawing arrived would
            // shuffle the list under the runner's finger. A treadmill Run gets the same square for
            // the same reason widened to the whole list (#232) — scrolling a winter of indoor Runs,
            // a row without one makes the text edge jump left and right at every change of kind.
            Box(modifier = Modifier.size(ThumbnailSize)) {
                if (session.runMode == RunMode.OUTDOOR.settingValue) {
                    row.thumbnail?.let { RouteThumbnailDrawing(it) }
                } else {
                    TreadmillDrawing()
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                HeaderRow(
                    modifier = Modifier.fillMaxWidth(),
                    start = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = dateStr, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                            // Beside the date rather than down among the four numbers, because it is
                            // not a measurement of the Run — it is what kind of Run it was (#275).
                            // Retagging a winter of sessions is unworkable without it: the runner
                            // would have to open each one to see whether they had already done it.
                            if (session.isWalk) {
                                Spacer(modifier = Modifier.width(6.dp))
                                WalkMarker()
                            }
                            MedalBadge(medals = row.medals)
                        }
                    },
                    end = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = formatDuration(session.durationSeconds),
                                maxLines = 1,
                                softWrap = false
                            )
                            EffortScoreBadge(score = session.effortScore)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                StatsRow(stats = historyRowStats(session), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * How many medals this Run holds (#51), beside its date — the thing worth spotting while scrolling
 * a year of Tuesdays.
 *
 * Just the count, not which metals: three medals is three medals, and a row cannot say gold, silver
 * and bronze in the space of a date without becoming a card. The Run's own page breaks it down
 * ([AchievementsCard]).
 */
@Composable
private fun MedalBadge(medals: Int) {
    if (medals <= 0) return
    Text(
        text = "🏆 $medals",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(start = 8.dp)
            .semantics {
                contentDescription = if (medals == 1) "1 achievement" else "$medals achievements"
            }
    )
}

/**
 * What this Run cost, at the end of its row (#62) — the number that makes a hard week look different
 * from an easy one at a glance while scrolling.
 *
 * At the right-hand end, after the clock, so it is the thing the rows are flush against: a Score is
 * two or three digits and its left edge moves, while what is being read down the list is one column
 * of numbers to compare. The four stats below stay four ([historyRowStats]) — a fifth column would
 * take about 31dp on the narrowest screen the app supports and shrink the whole row to fit it, which
 * is the price of the reorder #232 paid for.
 *
 * Written as a bolt rather than as a labelled number, for the reason `CONTEXT.md` gives: on screen
 * the bare word "Effort" already means how the runner *rated* the Run out of ten, and there is no
 * room on this line for "Effort Score" in full. The screen reader is told the whole name.
 *
 * Nothing at all for a Run with no Score — one recorded without a Strap, or one the backfill has not
 * reached yet. Not a dash: the stats below dash a number the Run *could* have and does not, and this
 * is a number that Run can never have.
 */
@Composable
private fun EffortScoreBadge(score: Int?) {
    if (score == null) return
    Text(
        text = "⚡ $score",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .padding(start = 8.dp)
            .semantics { contentDescription = "Effort Score $score" }
    )
}

/** How far apart the stats columns sit. */
private val StatColumnGap = 8.dp

/** The least room left between the date and the clock before the whole line starts to shrink. */
private val HeaderGap = 8.dp

/**
 * The line above the stats: when the Run was, and how long it took, pushed to opposite ends (#232).
 *
 * Shrunk as one thing when the two ends stop fitting, for the same reason the stats below are
 * ([fitToWidthScale]) — at 320dp with the system text turned up, a date carrying a medal count
 * leaves the clock beside it too little room, and what a plain Row does about that is break the
 * clock into one digit per line.
 */
@Composable
private fun HeaderRow(
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = { start(); end() }
    ) { measurables, constraints ->
        val gap = HeaderGap.roundToPx()
        // Measured with no width limit, so each end settles on one line before either is asked to
        // give width back.
        val (startPlaceable, endPlaceable) = measurables.map { it.measure(Constraints()) }
        val scale = fitToWidthScale(
            contentWidth = startPlaceable.width + gap + endPlaceable.width,
            availableWidth = constraints.maxWidth
        )
        val height = (maxOf(startPlaceable.height, endPlaceable.height) * scale).roundToInt()
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        val width = ((startPlaceable.width + gap + endPlaceable.width) * scale).roundToInt()
            .coerceIn(constraints.minWidth, constraints.maxWidth)

        layout(width, height) {
            fun placeAt(placeable: Placeable, x: Int) {
                // Centred on the line, so the clock still sits level with the date once one of the
                // two has shrunk further than the other.
                placeable.placeWithLayer(x, ((height - placeable.height * scale) / 2f).roundToInt()) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
            placeAt(startPlaceable, 0)
            placeAt(endPlaceable, width - (endPlaceable.width * scale).roundToInt())
        }
    }
}

/**
 * A Run's numbers, in columns of equal width, on one line each (#232).
 *
 * Laid out by hand rather than as a Row of Columns because the row has one size, not four: each
 * label and value is measured at the size it would like to be, and then the whole row is shrunk by
 * whatever its widest number needs to fit its column ([fitToWidthScale]). Shrinking each cell on its
 * own would leave four different type sizes side by side and the values sitting at four different
 * heights, which costs the list exactly what the reorder bought it — one line to read across, and
 * two columns to read down.
 */
@Composable
private fun StatsRow(stats: List<HistoryStat>, modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier,
        content = {
            stats.forEach { stat ->
                Text(
                    text = stat.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = stat.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    ) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        val gap = StatColumnGap.roundToPx()
        val columns = measurables.size / 2
        // Measured with no width limit, so every label and value settles on one line at its own
        // size before anything is asked to give width back.
        val placeables = measurables.map { it.measure(Constraints()) }
        val columnWidth = if (constraints.hasBoundedWidth) {
            statColumnWidth(constraints.maxWidth, gap, columns)
        } else {
            // Nothing to share out: the columns take the widest number among them, so they stay
            // equal even when the row is being measured rather than laid out.
            placeables.maxOf { it.width }
        }
        val scale = placeables.minOf { fitToWidthScale(it.width, columnWidth) }

        val rowHeight = (0 until columns)
            .maxOf { placeables[it * 2].height + placeables[it * 2 + 1].height }
        val height = (rowHeight * scale).roundToInt()
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        val width = (columns * columnWidth + (columns - 1) * gap)
            .coerceIn(constraints.minWidth, constraints.maxWidth)

        layout(width, height) {
            repeat(columns) { column ->
                val label = placeables[column * 2]
                val value = placeables[column * 2 + 1]
                val x = column * (columnWidth + gap)
                // Anchored at each column's own left edge, so the columns line up down the list
                // whether or not that row had to shrink.
                label.placeWithLayer(x, 0) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                value.placeWithLayer(x, (label.height * scale).roundToInt()) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }
}

/**
 * A run's length on the clock — hours only when there are hours.
 *
 * Shared with the achievements card (#49), which quotes best-effort times: the two are the same
 * question about the same kind of number, and written twice they would drift into two house styles
 * on one page.
 */
internal fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

// --- What the list looks like when the room runs out (#232) ---
//
// The row's shape has to hold at the smallest width and largest text the app supports, not just on
// the Pixel: every row now carries a 56dp square, and at 320dp there is only about 188dp left for
// four columns. These previews are the check — the same three rows at an ordinary size, at 320dp,
// and at 320dp with the system text turned up. Four columns, in the same places, in all three.

private fun previewRun(
    id: Long,
    runMode: RunMode,
    distanceKm: Double,
    durationSeconds: Long,
    avgBpm: Int,
    inTargetSeconds: Long,
    effortScore: Int? = null,
) = RunnerSession(
    id = id,
    startTime = 1_754_300_000_000L + id * 86_400_000L,
    endTime = 1_754_300_000_000L + id * 86_400_000L + durationSeconds * 1_000L,
    durationSeconds = durationSeconds,
    avgBpm = avgBpm,
    runMode = runMode.settingValue,
    distanceKm = distanceKm,
    targetZone = 2,
    zone2Seconds = inTargetSeconds,
    effortScore = effortScore,
)

private fun previewRows(): List<HistoryRow> {
    val outAndBack = RouteThumbnail(
        strokes = listOf(
            listOf(
                ThumbPoint(0.05f, 0.80f), ThumbPoint(0.30f, 0.55f), ThumbPoint(0.40f, 0.20f),
                ThumbPoint(0.70f, 0.10f), ThumbPoint(0.95f, 0.35f), ThumbPoint(0.75f, 0.65f),
                ThumbPoint(0.35f, 0.70f), ThumbPoint(0.10f, 0.90f),
            )
        )
    )
    return listOf(
        // A treadmill Run nobody stated a distance for: a square, and two dashes waiting for one.
        // Recorded without a Strap, so it has no Effort Score and shows none (#62).
        HistoryRow(
            session = previewRun(1, RunMode.TREADMILL, 0.0, 2_530, 152, 1_684),
            medals = 0,
            thumbnail = null,
        ),
        // A treadmill Run that was told how far it went.
        HistoryRow(
            session = previewRun(2, RunMode.TREADMILL, 7.25, 2_700, 145, 1_500, effortScore = 62),
            medals = 1,
            thumbnail = null,
        ),
        // An outdoor Run, with the widest numbers the row is likely to carry — a date carrying two
        // medals at one end and a three-digit Score at the other.
        HistoryRow(
            session = previewRun(3, RunMode.OUTDOOR, 21.10, 7_890, 148, 5_592, effortScore = 284),
            medals = 2,
            thumbnail = outAndBack,
        ),
    )
}

@Composable
private fun PreviewHistoryRows() {
    RunningAppTheme {
        Column(
            modifier = Modifier.padding(RunningUiTokens.PagePadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            previewRows().forEach { row ->
                SessionItem(row = row, isSelected = false, onClick = {}, onLongClick = {})
            }
        }
    }
}

@Preview(showBackground = true, name = "History rows")
@Composable
private fun PreviewHistoryRowsDefault() = PreviewHistoryRows()

@Preview(showBackground = true, widthDp = 320, name = "History rows, 320dp")
@Composable
private fun PreviewHistoryRowsNarrow() = PreviewHistoryRows()

@Preview(showBackground = true, widthDp = 320, fontScale = 1.3f, name = "History rows, 320dp at 1.3x text")
@Composable
private fun PreviewHistoryRowsNarrowLargeText() = PreviewHistoryRows()
