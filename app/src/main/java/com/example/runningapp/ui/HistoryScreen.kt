package com.example.runningapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.RouteThumbnail
import com.example.runningapp.analysis.ThumbPoint
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.run.RunMode
import com.example.runningapp.ui.theme.RunningAppTheme
import com.example.runningapp.ui.theme.RunningUiTokens
import java.text.SimpleDateFormat
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
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    val dateStr = sdf.format(Date(session.startTime))

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
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                StatsRow(stats = historyRowStats(session), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** How big the route drawing is, and how thick its line — a thumb-tip of route beside the stats. */
private val ThumbnailSize = 56.dp
private val ThumbnailLineWidth = 2.dp

/**
 * The shape of where a Run went, drawn beside it in the list (#51) — see [RouteThumbnail] for why
 * it is an outline and not a map.
 *
 * Each stroke is a stretch the recording actually covers, so a Run that paused is drawn as two
 * lines with a gap between them rather than one line across ground nobody witnessed.
 */
@Composable
private fun RouteThumbnailDrawing(thumbnail: RouteThumbnail) {
    ThumbnailCanvas { stroked ->
        thumbnail.strokes.forEach { stretch ->
            stroked {
                stretch.forEachIndexed { i, point ->
                    if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
            }
        }
    }
}

/**
 * The square every History row holds open, and the one line weight and colour everything in it is
 * drawn with — so a route and a treadmill read as one family of squares rather than as a drawing
 * beside an icon (#232).
 *
 * [content] is handed the one thing it needs: a way to stroke a line whose points are fractions of
 * the square, from (0,0) at the top left to (1,1) at the bottom right — the square [RouteThumbnail]
 * already speaks in.
 */
@Composable
private fun ThumbnailCanvas(content: (stroked: (ThumbnailStroke.() -> Unit) -> Unit) -> Unit) {
    val line = MaterialTheme.colorScheme.primary
    val stroke = with(LocalDensity.current) { ThumbnailLineWidth.toPx() }
    Canvas(modifier = Modifier.size(ThumbnailSize)) {
        // Inset by half the line's width, so anything drawn along the edge of the square is drawn
        // whole rather than shaved in half by it.
        val inset = stroke / 2f
        val span = size.minDimension - stroke
        content { build ->
            val path = Path()
            ThumbnailStroke(path, inset, span).build()
            drawPath(
                path = path,
                color = line,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

/** A line being drawn in the thumbnail's square, in fractions of it rather than in pixels. */
private class ThumbnailStroke(
    private val path: Path,
    private val inset: Float,
    private val span: Float,
) {
    fun moveTo(x: Float, y: Float) = path.moveTo(at(x), at(y))
    fun lineTo(x: Float, y: Float) = path.lineTo(at(x), at(y))
    fun close() = path.close()
    private fun at(fraction: Float) = inset + fraction * span
}

/**
 * The treadmill drawn beside an indoor Run (#232) — a deck, and the console raised at the end of it.
 *
 * Worth being honest about what this is: a route outline earns its square by telling you something,
 * since every Run's is a different shape. This is the same picture every time and tells you only
 * what kind of Run it was; it earns its square by keeping the text edge still down a list that mixes
 * indoor and outdoor Runs. So it is stroked at the same weight and in the same colour as a route —
 * the list has to read as one family of squares, not as an icon set beside a drawing.
 */
@Composable
private fun TreadmillDrawing() {
    ThumbnailCanvas { stroked ->
        // The deck, seen from the side and slightly above, so it is a running belt rather than a bar.
        stroked {
            moveTo(0.06f, 0.78f)
            lineTo(0.66f, 0.78f)
            lineTo(0.80f, 0.62f)
            lineTo(0.20f, 0.62f)
            close()
        }
        // The upright, from the far end of the deck to the console.
        stroked {
            moveTo(0.74f, 0.68f)
            lineTo(0.80f, 0.36f)
        }
        // The console.
        stroked {
            moveTo(0.62f, 0.32f)
            lineTo(0.92f, 0.32f)
            lineTo(0.92f, 0.18f)
            lineTo(0.62f, 0.18f)
            close()
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
        HistoryRow(
            session = previewRun(1, RunMode.TREADMILL, 0.0, 2_530, 152, 1_684),
            medals = 0,
            thumbnail = null,
        ),
        // A treadmill Run that was told how far it went.
        HistoryRow(
            session = previewRun(2, RunMode.TREADMILL, 7.25, 2_700, 145, 1_500),
            medals = 1,
            thumbnail = null,
        ),
        // An outdoor Run, with the widest numbers the row is likely to carry.
        HistoryRow(
            session = previewRun(3, RunMode.OUTDOOR, 21.10, 7_890, 148, 5_592),
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
