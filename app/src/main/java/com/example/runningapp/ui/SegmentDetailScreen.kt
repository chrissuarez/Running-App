package com.example.runningapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.ui.theme.RunningUiTokens

/** A floor, so the map is still a map on a small phone at a large text size. */
private val MapMinHeight = 160.dp

/**
 * One Segment's own page: its name, its ground, and how far that ground goes (#69).
 *
 * Minimal on purpose. There is no timing yet and nothing has ever been run against this stretch, so
 * a page with anything more on it would be promising a comparison the app cannot yet make.
 *
 * The map is interactive here, unlike the preview on a Run's page: this *is* the page, so there is
 * nothing underneath it for a drag to be stolen from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentDetailScreen(
    segment: Segment?,
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

        // Never scrolls, and the map takes whatever room the words leave rather than a fixed
        // height. Two reasons, and they are the same reason: this map can be panned, and a
        // pannable map inside a scrolling column steals the drag that was meant to scroll it —
        // while a fixed-height one plus two lines of text at 1.3x is taller than a small phone,
        // which is what would have made the column need to scroll (#63).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(RunningUiTokens.PagePadding),
        ) {
            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = MapMinHeight)) {
                    SegmentMapSurface(
                        segment = ground,
                        runBehind = emptyList(),
                        interactive = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
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
