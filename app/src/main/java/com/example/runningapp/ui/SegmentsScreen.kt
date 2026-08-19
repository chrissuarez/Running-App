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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.example.runningapp.data.Segment
import com.example.runningapp.ui.theme.RunningAppTheme
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * The Segments collection: every stretch of ground the runner has named, and the one way into them
 * (#69).
 *
 * Deliberately thin, like the Route library it sits beside. Everything it prints comes from
 * `SegmentModels.kt`, so what a runner reads is pinned by unit tests rather than by opening the
 * screen on a phone; the screen's own job is where the taps go.
 *
 * There is no way to *create* one from here, and that is not an omission. A Segment is a slice of a
 * Run's recorded track, so it can only be cut where that track is — on the Run's own page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentsScreen(
    segments: List<Segment>,
    message: String?,
    onOpen: (Segment) -> Unit,
    onRename: (Segment, String) -> Unit,
    onDelete: (Segment) -> Unit,
    onMessageShown: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }

    var renaming by rememberSaveable(stateSaver = SegmentIdSaver) { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable(stateSaver = SegmentIdSaver) { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Segments") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (segments.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(RunningUiTokens.PagePadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No segments yet.\n\nOpen a run you recorded outdoors, tap New segment " +
                        "on its map, and mark out a stretch worth naming.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(RunningUiTokens.PagePadding),
                verticalArrangement = Arrangement.spacedBy(RunningUiTokens.SectionSpacing),
            ) {
                items(segments, key = { it.id }) { segment ->
                    SegmentRow(
                        segment = segment,
                        onOpen = { onOpen(segment) },
                        onRename = { renaming = segment.id },
                        onDelete = { deleting = segment.id },
                    )
                }
            }
        }
    }

    segments.firstOrNull { it.id == renaming }?.let { segment ->
        RenameSegmentDialog(
            segment = segment,
            onDismiss = { renaming = null },
            onRename = { name ->
                onRename(segment, name)
                renaming = null
            },
        )
    }

    segments.firstOrNull { it.id == deleting }?.let { segment ->
        DeleteSegmentDialog(
            segment = segment,
            onDismiss = { deleting = null },
            onDelete = {
                onDelete(segment)
                deleting = null
            },
        )
    }
}

@Composable
private fun SegmentRow(
    segment: Segment,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RunningUiTokens.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Weighted rather than sized, and the name is allowed to wrap: a long name at a large
            // text scale must push the buttons nowhere (#63).
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = "Open ${segment.name}", onClick = onOpen),
            ) {
                Text(
                    text = segment.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = segmentRowSubtitle(segment),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onRename,
                modifier = Modifier.heightIn(min = RunningUiTokens.MinTouchTarget),
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Rename ${segment.name}")
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.heightIn(min = RunningUiTokens.MinTouchTarget),
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${segment.name}")
            }
        }
    }
}

/**
 * Asked before a Segment is forgotten, and shared by the collection and by a Segment's own page so
 * the two cannot ask it differently.
 */
@Composable
fun DeleteSegmentDialog(segment: Segment, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this segment?") },
        // Said outright, because it is the thing a runner is most likely to fear at this moment: a
        // Segment is a name for a piece of ground, and forgetting one costs no recording of
        // anything run.
        text = { Text("“${segment.name}” will be forgotten. Your runs are not affected.") },
        confirmButton = {
            TextButton(onClick = onDelete) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RenameSegmentDialog(
    segment: Segment,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by rememberSaveable(segment.id) { mutableStateOf(segment.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename segment") },
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
 * Which Segment a dialog is open for, kept across a rotation and a process death.
 *
 * The id rather than the row, for the reason the Route library keeps one: a Segment renamed or
 * deleted underneath an open dialog would otherwise leave a stale copy of itself on screen, and the
 * screen looks the row up afresh.
 */
private val SegmentIdSaver = Saver<Long?, Long>(
    save = { it ?: -1L },
    restore = { it.takeIf { id -> id >= 0 } },
)

@Preview(showBackground = true)
@Composable
private fun SegmentsScreenPreview() {
    RunningAppTheme {
        SegmentsScreen(
            segments = listOf(
                Segment(1, "Cemetery Hill", "", 410.0, 12, 0),
                Segment(2, "Canal straight", "", 1_620.0, null, 0),
            ),
            message = null,
            onOpen = {},
            onRename = { _, _ -> },
            onDelete = {},
            onMessageShown = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptySegmentsScreenPreview() {
    RunningAppTheme {
        SegmentsScreen(
            segments = emptyList(),
            message = null,
            onOpen = {},
            onRename = { _, _ -> },
            onDelete = {},
            onMessageShown = {},
            onBack = {},
        )
    }
}
