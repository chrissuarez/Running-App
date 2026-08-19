package com.example.runningapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.RunAnalysis
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.segments.SegmentCut
import com.example.runningapp.segments.defaultMarksFor
import com.example.runningapp.segments.segmentCutOf
import com.example.runningapp.segments.unbrokenStretchesOf
import com.example.runningapp.ui.theme.RunningUiTokens
import kotlin.math.roundToInt

/** A floor, so the map is still a map on a small phone at a large text size. */
private val MapMinHeight = 140.dp

/** Between the map and the first control, and part of the sum below, so it is named once. */
private val MapToControlsGap = 16.dp

/**
 * The tallest the controls under the map may be before they start scrolling inside themselves.
 *
 * The map is what gives room up, down to its floor — that is the whole layout (see
 * [SegmentCreateScreen]). But a landscape phone at a large text size can be too short to hold the
 * slider, the two marks, the summary, the name field and the Save button at *any* map size, and a
 * Column that runs out of room measures its last children at nothing at all: the Save button goes
 * silently missing rather than overflowing where anyone could see it. So past that point the floor
 * gives way too, and half the screen is the most the picture may keep.
 */
internal fun segmentControlsMaxHeight(available: Dp): Dp =
    (available - MapToControlsGap - MapMinHeight).coerceAtLeast(available / 2)

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
    session: RunnerSession?,
    samples: List<HrSample>,
    trackPoints: List<TrackPoint>,
    onSave: (SegmentCut, String) -> Unit,
    onBack: () -> Unit,
) {
    // Worked out here rather than by whoever navigated in, the way a Run's own page works its
    // analysis out ([SessionDetailScreen]): the breaks are what a cut is refused on, and only the
    // measurement knows where they are. Once per set of recordings, because a long Run is thousands
    // of fixes and dragging a handle recomposes this screen many times a second.
    val trackMap = remember(session, samples, trackPoints) {
        session?.let { RunAnalysis.of(it, samples, trackPoints).trackMap }
    }
    // An empty track means "not read yet" rather than "no track": this screen is only offered on a
    // Run the app already knows has one, so a Run that could reach it always has fixes to come.
    val stillReading = session == null || trackPoints.isEmpty()
    val defaultMarks = remember(trackMap) { trackMap?.let(::defaultMarksFor) }

    if (stillReading || trackMap == null || defaultMarks == null) {
        // Inside a page of its own with a top bar, never a bare spinner: a Run whose track holds no
        // unbroken stretch has nothing to cut and never will, and a screen with no way off it is
        // indistinguishable from one that is still loading.
        NothingToCutScreen(stillReading = stillReading, onBack = onBack)
        return
    }
    val runBehind = remember(trackMap) { unbrokenStretchesOf(trackMap) }

    // Kept across a rotation, so a runner who turned the phone sideways to place a mark more
    // precisely has not lost the mark they had already placed.
    //
    // Held against the Run rather than against the measurement of it. The recordings arrive on
    // three flows and the heart rate is usually last, so the measurement is rebuilt underneath a
    // screen the track alone has already made usable — and a mark placed or a name typed in that
    // moment would be thrown away by samples that changed nothing a Segment is cut on. What a cut
    // reads is the route and the breaks, and both of those came in with the track.
    val runId = session?.id
    var startMark by rememberSaveable(runId) { mutableStateOf(defaultMarks.first) }
    var endMark by rememberSaveable(runId) { mutableStateOf(defaultMarks.last) }
    var name by rememberSaveable(runId) { mutableStateOf("") }

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
        // The page itself never scrolls. The map takes whatever room the controls leave rather than
        // a fixed height, so at 1.3x text on a 320dp screen the Save button is still on the phone —
        // it shrinks the picture rather than pushing the thing the runner came here to press off
        // the bottom (#63). It also keeps a scrolling page from ever sitting over a map, which is
        // where a vertical drag gets stolen by the map instead of scrolling the page.
        //
        // The controls carry a ceiling ([segmentControlsMaxHeight]) and scroll within it, for the
        // screens too short to hold them at any map size at all. That scroll is theirs alone: the
        // map is outside it, so there is still no drag over the map for the map to take.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(RunningUiTokens.PagePadding),
        ) {
            val controlsMaxHeight = segmentControlsMaxHeight(maxHeight)
            Column(modifier = Modifier.fillMaxSize()) {
                Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    // The map's floor is kept by the sum above rather than by a minimum here: a
                    // minimum inside a weighted child is only ever coerced away by the exact height
                    // the weight hands down, so it would read as a promise and keep none of it.
                    Box(modifier = Modifier.fillMaxSize()) {
                        SegmentMapSurface(
                            segment = (cut as? SegmentCut.Cut)?.fixes.orEmpty(),
                            runBehind = runBehind,
                            // Pannable and zoomable: on a ten-kilometre run a
                            // three-hundred-metre mark is a few pixels, and a runner who
                            // cannot zoom in cannot see where their own handle landed. Safe
                            // here because no scroll ever passes over the map.
                            interactive = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(MapToControlsGap))

                Column(
                    modifier = Modifier
                        .heightIn(max = controlsMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // One slider with two handles rather than two sliders, because the pair is
                    // one choice: a runner moving the end of a hill is comparing it against where
                    // the start is, and the two have to be readable against one another on one
                    // line of ground.
                    RangeSlider(
                        value = startMark.toFloat()..endMark.toFloat(),
                        onValueChange = { range ->
                            startMark = range.start.roundToInt()
                            endMark = range.endInclusive.roundToInt()
                        },
                        valueRange = 0f..trackMap.route.lastIndex.toFloat(),
                        // A step per recorded fix, so a handle can only ever come to rest on
                        // a place the Run wrote down. Steps count the gaps between the stops,
                        // so this is one fewer.
                        steps = (trackMap.route.size - 2).coerceAtLeast(0),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Start and end of the segment along the run"
                            },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Start " +
                                segmentMarkLabel(trackMap.route[startMark].distanceMeters),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "End " +
                                segmentMarkLabel(trackMap.route[endMark].distanceMeters),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = segmentCutSummary(cut),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight =
                            if (cut is SegmentCut.Cut) FontWeight.SemiBold else FontWeight.Normal,
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
    }
}

/**
 * What a Run with nothing to cut gets, and what one still being read gets: either way, a page with
 * a way off it.
 *
 * The refusal is reachable even though a Run's page only offers this where it has a track, because
 * a track and an *unbroken stretch of* a track are different things — a Run whose every leg spans a
 * break has one and not the other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NothingToCutScreen(stillReading: Boolean, onBack: () -> Unit) {
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
            if (stillReading) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = NO_STRETCH_TO_CUT_MESSAGE,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
