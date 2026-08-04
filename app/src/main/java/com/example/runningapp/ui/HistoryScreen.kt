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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = dateStr, fontWeight = FontWeight.Bold)
                        MedalBadge(medals = row.medals)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(text = formatDuration(session.durationSeconds))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // Four columns of equal width, on every row, so the distance and the pace are in
                // the same place all the way down the list (#232). Equal width rather than each
                // column taking what it needs: what is being read is the column, not the row.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(StatColumnGap)
                ) {
                    historyRowStats(session).forEach { stat ->
                        StatSmall(
                            label = stat.label,
                            value = stat.value,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
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
    val line = MaterialTheme.colorScheme.primary
    val stroke = with(LocalDensity.current) { ThumbnailLineWidth.toPx() }
    Canvas(modifier = Modifier.size(ThumbnailSize)) {
        // Inset by half the line's width, so a route that runs along the edge of its own box is
        // drawn whole rather than shaved in half by it.
        val inset = stroke / 2f
        val span = size.minDimension - stroke
        thumbnail.strokes.forEach { stretch ->
            val path = Path()
            stretch.forEachIndexed { i, point ->
                val x = inset + point.x * span
                val y = inset + point.y * span
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = line,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
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
    val line = MaterialTheme.colorScheme.primary
    val stroke = with(LocalDensity.current) { ThumbnailLineWidth.toPx() }
    Canvas(modifier = Modifier.size(ThumbnailSize)) {
        // Laid out in fractions of the square, the same way a route is, and inset by half the
        // line's width so nothing drawn at the edge is shaved in half by it.
        val inset = stroke / 2f
        val span = size.minDimension - stroke
        fun x(fraction: Float) = inset + fraction * span
        fun y(fraction: Float) = inset + fraction * span

        fun stroked(build: Path.() -> Unit) = drawPath(
            path = Path().apply(build),
            color = line,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // The deck, seen from the side and slightly above, so it is a running belt rather than a bar.
        stroked {
            moveTo(x(0.06f), y(0.78f))
            lineTo(x(0.66f), y(0.78f))
            lineTo(x(0.80f), y(0.62f))
            lineTo(x(0.20f), y(0.62f))
            close()
        }
        // The upright, from the far end of the deck to the console.
        stroked {
            moveTo(x(0.74f), y(0.68f))
            lineTo(x(0.80f), y(0.36f))
        }
        // The console.
        stroked {
            moveTo(x(0.62f), y(0.32f))
            lineTo(x(0.92f), y(0.32f))
            lineTo(x(0.92f), y(0.18f))
            lineTo(x(0.62f), y(0.18f))
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

/** How far apart the four stats columns sit. */
private val StatColumnGap = 8.dp

@Composable
fun StatSmall(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        FitToWidth {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
        FitToWidth {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Draws whatever is inside it at its natural size, shrunk just enough to fit the width it is given
 * (#232) — see [fitToWidthScale] for why shrinking rather than clipping, ellipsising or wrapping.
 *
 * The content is measured with no width limit, so it settles on one line and its own size first,
 * and only then is told how much of that it may keep.
 */
@Composable
private fun FitToWidth(content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val placeable = measurables.first()
            .measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        val scale = fitToWidthScale(placeable.width, constraints.maxWidth)
        val width = placeable.width
            .coerceAtMost(constraints.maxWidth)
            .coerceAtLeast(constraints.minWidth)
        val height = (placeable.height * scale).roundToInt()
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            // Anchored at the top left, so a shrunk column still starts where the unshrunk one
            // beside it does and the list keeps one left edge per column.
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
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

private fun previewSession(
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
            session = previewSession(1, RunMode.TREADMILL, 0.0, 2_530, 152, 1_684),
            medals = 0,
            thumbnail = null,
        ),
        // A treadmill Run that was told how far it went.
        HistoryRow(
            session = previewSession(2, RunMode.TREADMILL, 7.25, 2_700, 145, 1_500),
            medals = 1,
            thumbnail = null,
        ),
        // An outdoor Run, with the widest numbers the row is likely to carry.
        HistoryRow(
            session = previewSession(3, RunMode.OUTDOOR, 21.10, 7_890, 148, 5_592),
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
