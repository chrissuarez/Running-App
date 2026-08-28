package com.example.runningapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.RouteThumbnail
import com.example.runningapp.analysis.ThumbPoint
import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteSource
import com.example.runningapp.ui.theme.RunningAppTheme
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * The Route library: every course the runner keeps, and the one way into it (#54).
 *
 * Deliberately thin. Everything it prints comes from [routeRowSubtitle] and its neighbours in
 * `RouteModels.kt`, so what a runner reads is pinned by unit tests rather than by opening the screen
 * on a phone; the screen's own job is where the taps go. The shape drawn beside each row is worked
 * out the same way, by [com.example.runningapp.analysis.courseThumbnailOf] before the row is handed
 * over (#59).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesScreen(
    rows: List<RouteRowUi>,
    isImporting: Boolean,
    message: String?,
    onImport: () -> Unit,
    onRename: (Route, String) -> Unit,
    onDelete: (Route) -> Unit,
    onMessageShown: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Long, because a refusal is a paragraph telling the runner what to try next, and it is the
    // only place they are told.
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
            onMessageShown()
        }
    }

    var renaming by rememberSaveable(stateSaver = RouteIdSaver) { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable(stateSaver = RouteIdSaver) { mutableStateOf<Long?>(null) }

    // The Import button floats over the list rather than in it, so the list has to be told how tall
    // it is: without that, scrolling to the end leaves the last route's Rename and Delete sitting
    // underneath the button, where no tap can reach them. Measured rather than assumed, because the
    // button grows with the phone's text size and a guessed constant is wrong at 1.3× — which is
    // where a hidden row matters most (#63).
    val density = LocalDensity.current
    var importButtonHeight by remember { mutableStateOf(0.dp) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Routes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.onSizeChanged {
                    importButtonHeight = with(density) { it.height.toDp() }
                },
                // Deaf while a file is being read rather than greyed out. An import is usually
                // instant, so this is a guard against a double tap opening two pickers rather than
                // a state a runner will sit looking at.
                onClick = { if (!isImporting) onImport() },
                expanded = true,
                icon = {
                    if (isImporting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                },
                text = { Text(if (isImporting) "Reading…" else "Import GPX") },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(RunningUiTokens.PagePadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No routes yet.\n\nImport a GPX file — from Strava, Garmin Connect, " +
                        "Komoot, or anywhere else you have one — and it will be saved here.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = RunningUiTokens.PagePadding,
                    end = RunningUiTokens.PagePadding,
                    top = RunningUiTokens.PagePadding,
                    // Room for the floating Import button, and a gap so the last row clears it
                    // rather than touching it.
                    bottom = RunningUiTokens.PagePadding + importButtonHeight +
                        RunningUiTokens.SectionSpacing,
                ),
                verticalArrangement = Arrangement.spacedBy(RunningUiTokens.SectionSpacing),
            ) {
                items(rows, key = { it.route.id }) { row ->
                    RouteRow(
                        row = row,
                        onRename = { renaming = row.route.id },
                        onDelete = { deleting = row.route.id },
                    )
                }
            }
        }
    }

    rows.map { it.route }.firstOrNull { it.id == renaming }?.let { route ->
        RenameRouteDialog(
            route = route,
            onDismiss = { renaming = null },
            onRename = { name ->
                onRename(route, name)
                renaming = null
            },
        )
    }

    rows.map { it.route }.firstOrNull { it.id == deleting }?.let { route ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this route?") },
            // Said outright, because it is the thing a runner is most likely to fear at this
            // moment: a Route is a plan, and forgetting one costs no recording of anything run.
            text = { Text("“${route.name}” will be forgotten. Your runs are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(route)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RouteRow(row: RouteRowUi, onRename: () -> Unit, onDelete: () -> Unit) {
    val route = row.route
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RunningUiTokens.CardPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The square is held open on every row, drawn or not, for the reason History holds one
            // open (#51): a course's shape is worked out after the row is on screen, and a row that
            // widened when its drawing arrived would shuffle the list under the runner's finger. It
            // stays empty for a course too short or too damaged to have a shape, which keeps the
            // name edge straight down a library that holds one.
            Box(modifier = Modifier.size(ThumbnailSize)) {
                row.thumbnail?.let { RouteThumbnailDrawing(it) }
            }
            // Weighted rather than sized, and the name is allowed to wrap: a long route name at a
            // large text scale must push the buttons nowhere (#63).
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = route.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = routeRowSubtitle(route),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onRename,
                modifier = Modifier.heightIn(min = RunningUiTokens.MinTouchTarget),
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Rename ${route.name}")
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.heightIn(min = RunningUiTokens.MinTouchTarget),
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${route.name}")
            }
        }
    }
}

@Composable
private fun RenameRouteDialog(route: Route, onDismiss: () -> Unit, onRename: (String) -> Unit) {
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
 * Which Route a dialog is open for, kept across a rotation and a process death.
 *
 * The id rather than the row: a Route that is renamed or deleted underneath an open dialog would
 * otherwise leave a stale copy of itself on screen, and the screen looks the row up afresh.
 */
private val RouteIdSaver = androidx.compose.runtime.saveable.Saver<Long?, Long>(
    save = { it ?: -1L },
    restore = { it.takeIf { id -> id >= 0 } },
)

@Preview(showBackground = true)
@Composable
private fun RoutesScreenPreview() {
    RunningAppTheme {
        RoutesScreen(
            rows = listOf(
                RouteRowUi(
                    route = Route(
                        id = 1,
                        name = "Regent's Park outer loop",
                        distanceMeters = 4_215.0,
                        elevationGainMeters = 27.4,
                        polyline = "",
                        createdAtMillis = 0,
                        source = RouteSource.IMPORTED,
                    ),
                    thumbnail = RouteThumbnail(
                        listOf(
                            listOf(
                                ThumbPoint(0.15f, 0.85f), ThumbPoint(0.05f, 0.35f),
                                ThumbPoint(0.45f, 0.05f), ThumbPoint(0.9f, 0.3f),
                                ThumbPoint(0.75f, 0.8f), ThumbPoint(0.15f, 0.85f),
                            )
                        )
                    ),
                ),
                RouteRowUi(
                    route = Route(
                        id = 2,
                        name = "Canal towpath out and back",
                        distanceMeters = 10_050.0,
                        elevationGainMeters = null,
                        polyline = "",
                        createdAtMillis = 0,
                        source = RouteSource.IMPORTED,
                    ),
                    thumbnail = RouteThumbnail(
                        listOf(
                            listOf(
                                ThumbPoint(0.45f, 1f), ThumbPoint(0.4f, 0.5f),
                                ThumbPoint(0.6f, 0.2f), ThumbPoint(0.55f, 0f),
                            )
                        )
                    ),
                ),
            ),
            isImporting = false,
            message = null,
            onImport = {},
            onRename = { _, _ -> },
            onDelete = {},
            onMessageShown = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyRoutesScreenPreview() {
    RunningAppTheme {
        RoutesScreen(
            rows = emptyList(),
            isImporting = false,
            message = null,
            onImport = {},
            onRename = { _, _ -> },
            onDelete = {},
            onMessageShown = {},
            onBack = {},
        )
    }
}
