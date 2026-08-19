package com.example.runningapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.MapFix
import com.example.runningapp.data.Segment
import com.example.runningapp.data.SegmentEffortRow
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.ui.theme.RunningUiTokens

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

            items(shown, key = { it.effortId }) { effort ->
                SegmentEffortRowUi(effort)
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
 * One effort in the list: when it was run, how long it took, and how quick that was.
 *
 * The time is the column on its own at the end, because it is the number the runner is scanning the
 * list for; the date and the pace read as one line under each other so the row survives a narrow
 * phone at a large text size (#63).
 */
@Composable
private fun SegmentEffortRowUi(effort: SegmentEffortUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
