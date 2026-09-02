package com.example.runningapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
     * Every length of this course's family, shortest first — one entry for a course in no family
     * (#421).
     *
     * The chips are drawn only where there are two or more. A family of one is a course with a name
     * on it and nothing to switch between, and a single chip that cannot be turned off would be a
     * control that does nothing ([routeSiblings]).
     */
    siblings: List<RouteHeader>,
    /** Which length is being shown — always [route]'s own id once the page has settled (#421). */
    selectedId: Long?,
    /** Tapping another length's chip. Everything on the page below follows it. */
    onSelectLength: (Long) -> Unit,
    /** The family names the library already holds, offered so the runner need not retype one. */
    familyNames: List<String>,
    /** Typing or clearing the family box. Null takes the course out of its family. */
    onSetFamily: (RouteHeader, String?) -> Unit,
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
    /**
     * Forgetting this one length (#421).
     *
     * Here as well as in the library because a family's library row has no bin — it stands for
     * several courses, and one tap that forgot all of them would be the most destructive thing on
     * that screen wearing the same icon as the least. This page shows exactly one length, so the bin
     * on it is unambiguous.
     */
    onDelete: (RouteHeader) -> Unit,
    onBack: () -> Unit,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var editingFamily by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }

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
                    IconButton(onClick = { deleting = true }, enabled = route != null) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete route")
                    }
                },
            )
        },
    ) { padding ->
        if (route == null) {
            // The row is watched, so this is the first read not having landed yet — the page is
            // drawn the instant the row is tapped, and the read answers frames later.
            //
            // It is not "the course was deleted". The bin on this page leaves for the library the
            // moment it is tapped ([onDelete]), and the library's own bin is on the page underneath
            // this one — so neither door reaches here with the row already gone. There is no pop to
            // make, and none is made.
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
            if (siblings.size > 1) {
                item {
                    // Above the map rather than below it, because the chip is what the map, the
                    // numbers and every Run underneath are all about: the runner picks the length
                    // first and then reads the page (#421).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Scrolled sideways rather than wrapped: a family of six lengths at 1.3×
                            // text is wider than the phone, and a wrapped second line of chips
                            // pushes the map off the first screen (#63).
                            .horizontalScroll(rememberScrollState())
                            .semantics { contentDescription = ROUTE_FAMILY_LENGTHS_LABEL },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        siblings.forEach { sibling ->
                            FilterChip(
                                selected = sibling.id == selectedId,
                                onClick = { onSelectLength(sibling.id) },
                                label = { Text(routeLengthChipLabel(sibling.distanceMeters)) },
                                modifier = Modifier.heightIn(min = RunningUiTokens.MinTouchTarget),
                            )
                        }
                    }
                }
            }

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
                // Under the numbers rather than beside the name: putting a course in a family is a
                // thing the runner does once, and the page is mostly about the course itself.
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editingFamily = true },
                ) {
                    Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
                        Text(
                            text = ROUTE_FAMILY_FIELD_LABEL,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = route.family ?: ROUTE_FAMILY_EMPTY_HINT,
                            style = MaterialTheme.typography.bodyMedium,
                        )
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

    if (editingFamily && route != null) {
        RouteFamilyDialog(
            route = route,
            familyNames = familyNames,
            onDismiss = { editingFamily = false },
            onSetFamily = { family ->
                onSetFamily(route, family)
                editingFamily = false
            },
        )
    }

    if (deleting && route != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this route?") },
            // The library's own words, because it is the same promise: a Route is a plan, and
            // forgetting one costs no recording of anything run.
            text = { Text("“${route.name}” will be forgotten. Your runs are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete(route)
                    // Left straight away: what is underneath is the page of a course that no longer
                    // exists, and waiting for the read to come back empty would show the runner a
                    // spinner where their course was.
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
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

/**
 * Putting a course in a family, or taking it out of one (#421).
 *
 * A box to type in **and** the names already in use, because both are the same act done at different
 * moments: the first length of a family is typed, and every one after it is picked. Retyping is
 * where a family silently splits in two — "Cuckoo trail" beside "Cuckoo Trail" — so the names the
 * library holds are offered to be tapped rather than remembered.
 *
 * Clearing the box takes the course out of its family, and it is said in as many words on the
 * button: a runner who empties a text box and taps Save is entitled to know what that did.
 */
@Composable
private fun RouteFamilyDialog(
    route: RouteHeader,
    familyNames: List<String>,
    onDismiss: () -> Unit,
    onSetFamily: (String?) -> Unit,
) {
    var family by rememberSaveable(route.id) { mutableStateOf(route.family.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Family") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = ROUTE_FAMILY_EMPTY_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = family,
                    onValueChange = { family = it },
                    singleLine = true,
                    label = { Text(ROUTE_FAMILY_FIELD_LABEL) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSetFamily(family) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (familyNames.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        familyNames.forEach { name ->
                            SuggestionChip(
                                onClick = { family = name },
                                label = { Text(name) },
                                modifier = Modifier.heightIn(min = RunningUiTokens.MinTouchTarget),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSetFamily(family) }) {
                Text(if (family.isBlank()) "Remove from family" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
