package com.example.runningapp.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.RouteThumbnail
import com.example.runningapp.analysis.ThumbPoint
import com.example.runningapp.data.RouteHeader
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
    /**
     * The library already folded: one row per family, the rest a row each (#421) — see
     * [routeLibraryRows].
     */
    rows: List<RouteLibraryRow>,
    isImporting: Boolean,
    message: String?,
    onImport: () -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (RouteHeader) -> Unit,
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
                // Told apart by kind as well as by value, so a family a runner named "5" and the
                // course whose id is 5 cannot be handed the same key.
                items(rows, key = { row -> row.family?.let { "family:$it" } ?: "route:${row.openRouteId}" }) { row ->
                    RouteRow(
                        row = row,
                        onOpen = { onOpen(row.openRouteId) },
                        // A family row has no bin: it stands for several courses, and one tap that
                        // forgot all of them would be the most destructive thing on the screen
                        // wearing the same icon as the least. Each length is forgotten from its own
                        // page, where the runner can see which one they are looking at (#421).
                        onDelete = row.route?.let { route -> { deleting = route.id } },
                    )
                }
            }
        }
    }

    // Against the id rather than the row, and only once there is one: `it.route?.id == deleting`
    // with nothing being deleted would match the first family row, whose course is null.
    deleting?.let { id -> rows.firstOrNull { it.route?.id == id }?.route }?.let { route ->
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

/**
 * One row of the library: a course, or a whole family of lengths (#54, #421).
 *
 * The row itself is the tap, and it opens a course's own page (#420) — one primary target per row,
 * which is why renaming moved onto that page and left the pencil behind. The bin stays on a lone
 * course: forgetting one is a thing a runner does *to* a list, and making them open a page to do it
 * would make tidying up a library a page at a time. A family row has no bin
 * ([onDelete] is null there), because the row is not one course to forget.
 */
@Composable
private fun RouteRow(row: RouteLibraryRow, onOpen: () -> Unit, onDelete: (() -> Unit)?) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
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
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = row.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            onDelete?.let { forget ->
                IconButton(
                    onClick = forget,
                    modifier = Modifier.heightIn(min = RunningUiTokens.MinTouchTarget),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete ${row.title}")
                }
            }
        }
    }
}

/**
 * Which Route the delete dialog is open for, kept across a rotation and a process death.
 *
 * The id rather than the row: a Route renamed on its own page (#420), or deleted underneath the
 * open dialog, would otherwise leave a stale copy of itself on screen, and the screen looks the row
 * up afresh — which is what makes the dialog name the course by the name the library holds now.
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
                RouteLibraryRow(
                    title = "Regent's Park outer loop",
                    subtitle = "4.22 km · 27 m up",
                    thumbnail = RouteThumbnail(
                        listOf(
                            listOf(
                                ThumbPoint(0.15f, 0.85f), ThumbPoint(0.05f, 0.35f),
                                ThumbPoint(0.45f, 0.05f), ThumbPoint(0.9f, 0.3f),
                                ThumbPoint(0.75f, 0.8f), ThumbPoint(0.15f, 0.85f),
                            )
                        )
                    ),
                    openRouteId = 1,
                    family = null,
                    lengthCount = 1,
                    route = RouteHeader(
                        id = 1,
                        name = "Regent's Park outer loop",
                        distanceMeters = 4_215.0,
                        elevationGainMeters = 27.4,
                        createdAtMillis = 0,
                        source = RouteSource.IMPORTED,
                    ),
                ),
                RouteLibraryRow(
                    title = "Cuckoo Trail",
                    subtitle = "3 lengths · 5.00–12.00 km",
                    thumbnail = RouteThumbnail(
                        listOf(
                            listOf(
                                ThumbPoint(0.45f, 1f), ThumbPoint(0.4f, 0.5f),
                                ThumbPoint(0.6f, 0.2f), ThumbPoint(0.55f, 0f),
                            )
                        )
                    ),
                    openRouteId = 2,
                    family = "Cuckoo Trail",
                    lengthCount = 3,
                    route = null,
                ),
            ),
            isImporting = false,
            message = null,
            onImport = {},
            onOpen = {},
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
            onOpen = {},
            onDelete = {},
            onMessageShown = {},
            onBack = {},
        )
    }
}
