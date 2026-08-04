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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.RouteThumbnail
import com.example.runningapp.ui.theme.RunningUiTokens
import com.example.runningapp.data.averagePaceText
import com.example.runningapp.data.inTargetZoneSeconds
import java.text.SimpleDateFormat
import java.util.*

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
            // Nothing at all where there is no route to draw — a treadmill Run, or one recorded
            // before the app kept tracks. The row keeps its old shape rather than reserving an
            // empty square for a drawing that is never coming.
            row.thumbnail?.let { RouteThumbnailImage(it) }
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = dateStr, fontWeight = FontWeight.Bold)
                        TrophyBadge(medals = row.medals)
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatSmall(label = "Avg HR", value = "${session.avgBpm}")
                    if (session.runMode == "outdoor") {
                        StatSmall(label = "Dist", value = "%.2f km".format(session.distanceKm))
                        // Derived, not read from the stored column (#163).
                        StatSmall(label = "Pace", value = session.averagePaceText)
                    }
                    StatSmall(label = "Target", value = formatDuration(session.inTargetZoneSeconds))
                }
            }
        }
    }
}

/** How big the route drawing is, and how thick its line — a thumb-tip of route beside the stats. */
private val ThumbnailSize = 56.dp
private val ThumbnailLineWidth = 2.dp

/**
 * The shape of where a Run went, drawn beside it in the list (#51).
 *
 * A line and nothing else: no map under it and no zone colours on it. At this size streets are a
 * smudge and five colours are a smear, and the question a runner is asking while scrolling is only
 * "which run was that?" — which the shape answers on its own. The Run's own page has the map that
 * answers the rest ([RunTrackMapCard]).
 *
 * Each stroke is a stretch the recording actually covers, so a Run that paused is drawn as two
 * lines with a gap between them rather than one line across ground nobody witnessed.
 */
@Composable
private fun RouteThumbnailImage(thumbnail: RouteThumbnail) {
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
 * How many medals this Run holds (#51), beside its date — the thing worth spotting while scrolling
 * a year of Tuesdays.
 *
 * Just the count, not which metals: three medals is three medals, and a row cannot say gold, silver
 * and bronze in the space of a date without becoming a card. The Run's own page breaks it down
 * ([AchievementsCard]).
 */
@Composable
private fun TrophyBadge(medals: Int) {
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

@Composable
fun StatSmall(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
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
