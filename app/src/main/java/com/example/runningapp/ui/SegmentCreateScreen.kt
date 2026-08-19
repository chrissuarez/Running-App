package com.example.runningapp.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.TrackMap
import com.example.runningapp.segments.SegmentCut
import com.example.runningapp.segments.defaultMarksFor
import com.example.runningapp.segments.segmentCutOf
import com.example.runningapp.segments.unbrokenStretchesOf
import com.example.runningapp.ui.theme.RunningUiTokens
import kotlin.math.roundToInt

/** A floor, so the map is still a map on a small phone at a large text size. */
private val MapMinHeight = 140.dp

/**
 * Cutting a Segment out of a Run the runner already did (#69).
 *
 * The two marks are handles on a slider rather than taps on the map, and that is the point rather
 * than a convenience: a Segment's geometry is a claim about ground the Run recorded, and a handle
 * that can only stop on a recorded fix cannot make that claim wrong. A tap near a folded-back route
 * can — two legs of an out-and-back sit a few metres apart on screen, and the nearest fix to a
 * fingertip is as likely to be on the way home as on the way out.
 *
 * Everything the screen decides — what the marks cut out, and whether it may be kept — is
 * [segmentCutOf]'s, and everything it says is `SegmentModels.kt`'s. What is left here is where the
 * taps go.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentCreateScreen(
    trackMap: TrackMap,
    onSave: (SegmentCut, String) -> Unit,
    onBack: () -> Unit,
) {
    val defaultMarks = remember(trackMap) { defaultMarksFor(trackMap) }
    val context = remember(trackMap) { unbrokenStretchesOf(trackMap) }

    if (defaultMarks == null) {
        NoStretchToCutScreen(onBack = onBack)
        return
    }

    // Kept across a rotation, so a runner who turned the phone sideways to place a mark more
    // precisely has not lost the mark they had already placed.
    var startMark by rememberSaveable(trackMap) { mutableStateOf(defaultMarks.first) }
    var endMark by rememberSaveable(trackMap) { mutableStateOf(defaultMarks.last) }
    var name by rememberSaveable(trackMap) { mutableStateOf("") }

    val cut = remember(trackMap, startMark, endMark) { segmentCutOf(trackMap, startMark, endMark) }
    val canSave = cut is SegmentCut.Cut && name.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New segment") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        // Never scrolls. The map takes whatever room the controls leave rather than a fixed height,
        // so at 1.3x text on a 320dp screen the Save button is still on the phone — it shrinks the
        // picture rather than pushing the thing the runner came here to press off the bottom (#63).
        // It also keeps a scrolling column from ever sitting over a map, which is where a vertical
        // drag gets stolen by the map instead of scrolling the page.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(RunningUiTokens.PagePadding),
        ) {
            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = MapMinHeight)) {
                    SegmentMapSurface(
                        segment = (cut as? SegmentCut.Cut)?.fixes.orEmpty(),
                        context = context,
                        interactive = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // One slider with two handles rather than two sliders, because the pair is one choice:
            // a runner moving the end of a hill is comparing it against where the start is, and the
            // two have to be readable against one another on one line of ground.
            RangeSlider(
                value = startMark.toFloat()..endMark.toFloat(),
                onValueChange = { range ->
                    startMark = range.start.roundToInt()
                    endMark = range.endInclusive.roundToInt()
                },
                valueRange = 0f..trackMap.route.lastIndex.toFloat(),
                // A step per recorded fix, so a handle can only ever come to rest on a place the
                // Run wrote down. Steps count the gaps between the stops, so this is one fewer.
                steps = (trackMap.route.size - 2).coerceAtLeast(0),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Start and end of the segment along the run" },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Start " + segmentMarkLabel(trackMap.route[startMark].distanceMeters),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "End " + segmentMarkLabel(trackMap.route[endMark].distanceMeters),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = segmentCutSummary(cut),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (cut is SegmentCut.Cut) FontWeight.SemiBold else FontWeight.Normal,
                color = if (cut is SegmentCut.Cut) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                placeholder = { Text("Cemetery Hill") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onSave(cut, name) },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = RunningUiTokens.MinTouchTarget),
            ) {
                Text("Save segment")
            }
        }
    }
}

/**
 * What a Run with nothing to cut gets: the reason, and the way back.
 *
 * Reachable even though the detail page only offers this on a Run with a track, because a track and
 * an *unbroken stretch of* a track are different things — a Run whose every leg spans a break has
 * one and not the other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoStretchToCutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New segment") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(RunningUiTokens.PagePadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = NO_STRETCH_TO_CUT_MESSAGE,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}
