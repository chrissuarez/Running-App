package com.example.runningapp.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.MapFix
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * How tall the course's map is.
 *
 * Fixed rather than taking whatever room the words leave, for [SegmentDetailScreen]'s reason: under
 * it is a list of every Run remembered here, which is one row on a new course and fifty on an old
 * one, and a map that shrank as that list grew would stop showing the ground the page is about.
 */
private val MapHeight = 220.dp

/**
 * One Route's own page: the course drawn, what it costs, and every Run remembered on it (#420).
 *
 * The map is not interactive and the page scrolls instead — the lesson [SegmentDetailScreen] paid
 * for (#69): a pannable map inside a scrolling column steals the drag meant to scroll the page, and
 * between panning a map and reaching the runs, reaching the runs is what the runner came for.
 *
 * Renaming lives here rather than on the library row, so a row in the library has one primary tap
 * target: the row itself, which opens this page.
 *
 * There is deliberately no "start a run on this course" button. Starting a Run reaches into the run
 * screen and its settings, which is a second feature — the course is picked before START, where it
 * already is (#56).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(
    route: RouteHeader?,
    /**
     * The course drawn, or empty while the line is still being fetched — the line is read separately
     * from the row it belongs to ([com.example.runningapp.data.RouteDao.getRouteHeaderFlow]).
     */
    line: List<MapFix>,
    /**
     * Every Run remembered on this course, or **null while that has not been read yet**.
     *
     * Told apart from an empty list on purpose, the rule the record book's own page keeps
     * (`recordDetailNotReadYet`): the row and the Runs are two reads, the row can land first, and an
     * empty list drawn in the gap would tell a runner who has been round this course fifty times
     * that they never had — in the moment right after they tapped it.
     */
    runs: List<RouteRunUi>?,
    onRename: (RouteHeader, String) -> Unit,
    onOpenRun: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(route?.name ?: "Route") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { renaming = true }, enabled = route != null) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename route")
                    }
                },
            )
        },
    ) { padding ->
        if (route == null) {
            // The row is watched, so this is the first read not having landed yet — the page is
            // drawn the instant the row is tapped, and the read answers frames later.
            //
            // It is not "the course was deleted": forgetting one is offered in the library alone,
            // which is the page underneath this one, so a runner has to leave here before they can
            // reach the bin. There is no pop to make, and none is made.
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val best = remember(runs) { runs?.let(::routeBestOf) }
        val average = remember(runs) { runs?.let(::routeAverageTimeLabel) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(RunningUiTokens.PagePadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.fillMaxWidth().height(MapHeight)) {
                        // The Segments' surface rather than a new one (#69, #420): a course is a
                        // piece of ground with no heart rate on it, exactly as a Segment is, so the
                        // zone-coloured map a Run gets would be inventing a reading this row does
                        // not hold. Nothing drawn behind it — there is no Run this course sits
                        // inside of.
                        SegmentMapSurface(
                            segment = line,
                            runBehind = emptyList(),
                            interactive = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            item {
                Text(
                    text = routeDistanceLabel(route.distanceMeters),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = routeElevationLabel(route.elevationGainMeters),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Runs on the course, but not one of them inside the band. Said in words rather than
            // left as a missing card: the runner can see their times in the list below and would
            // otherwise be left wondering why none of them is a best (#420).
            if (best == null && !runs.isNullOrEmpty()) {
                item {
                    Text(
                        text = NO_COUNTED_ROUTE_RUNS_MESSAGE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (best != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(RunningUiTokens.CardPadding),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            TimeStat(
                                modifier = Modifier.weight(1f),
                                title = ROUTE_BEST_TIME_TITLE,
                                value = best.timeLabel,
                            )
                            // Never absent beside a best: both are drawn from the same runs, so a
                            // best existing is a counted run existing.
                            average?.let {
                                TimeStat(
                                    modifier = Modifier.weight(1f),
                                    title = ROUTE_AVERAGE_TIME_TITLE,
                                    value = it,
                                )
                            }
                        }
                    }
                }
            }

            item {
                if (runs == null) {
                    // Not read yet, which is not "none" — see the [runs] parameter. Nothing is said
                    // either way until the read lands.
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (runs.isEmpty()) {
                    Text(
                        text = NO_ROUTE_RUNS_MESSAGE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = ROUTE_RUNS_TITLE,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = routeRunCountLabel(runs.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(runs.orEmpty(), key = { it.sessionId }) { run ->
                RouteRunRowUi(run = run, onOpen = { onOpenRun(run.sessionId) })
            }
        }
    }

    if (renaming && route != null) {
        RenameRouteOnDetailDialog(
            route = route,
            onDismiss = { renaming = false },
            onRename = { name ->
                onRename(route, name)
                renaming = false
            },
        )
    }
}

@Composable
private fun TimeStat(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * One Run on the course: when it was, how far it actually went, and what it took.
 *
 * A Run that takes no part in the best or the average keeps its row and is told so in words, in the
 * words of its own reason ([RouteRunUi.notCountedNote]) — never dropped. A run that vanished from
 * its own course's page with no explanation reads as lost data.
 */
@Composable
private fun RouteRunRowUi(run: RouteRunUi, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RunningUiTokens.MinTouchTarget)
            .clickable(onClick = onOpen)
            .semantics {
                contentDescription = (if (run.isBest) "Best time, " else "") +
                    "${run.dateLabel}, ${run.timeLabel}, ${run.distanceLabel}" +
                    (run.notCountedNote?.let { ". $it" } ?: "")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = run.dateLabel, style = MaterialTheme.typography.bodyLarge)
            // The distance is printed whether the Run counts or not, and the note goes *under* it
            // rather than over it: how far this Run actually went is the very number that explains
            // why it does not count, so replacing one with the other would take away the answer to
            // the question the note raises.
            Text(
                text = run.distanceLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            run.notCountedNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (run.isBest) {
            Text(
                text = "Best",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(
            text = run.timeLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Renaming, from the page the course is on.
 *
 * Its own dialog rather than the library's, which is private to that screen and keyed by a row in a
 * list. Here there is one course and it is the page's subject.
 */
@Composable
private fun RenameRouteOnDetailDialog(
    route: RouteHeader,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by rememberSaveable(route.id) { mutableStateOf(route.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename route") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRename(name) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
