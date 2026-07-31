package com.example.runningapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.SPLIT_METERS
import com.example.runningapp.analysis.Split
import com.example.runningapp.data.formatMinutesPerKm
import com.example.runningapp.ui.theme.RunningUiTokens
import kotlin.math.roundToInt

/** How tall the relative-pace bar is, and the shortest a bar may be drawn while still being a bar. */
private val BarHeight = 20.dp
private const val SHORTEST_VISIBLE_BAR = 0.06f

/**
 * The run kilometre by kilometre (#45).
 *
 * The bar is the point of the table: pace read as numbers takes a column of arithmetic to see the
 * shape of a run in, and read as bars it takes a glance. Longest bar is the slowest kilometre,
 * which is Strava's convention and the one that matches how a runner talks about a run — the long
 * bar is the hill, the short one is the finish.
 *
 * Draws nothing at all when the run has no splits: a treadmill run and a run with no route have no
 * kilometres that were measured off the ground, and an empty table would only invite the question.
 */
@Composable
fun SplitsTable(splits: List<Split>, modifier: Modifier = Modifier) {
    if (splits.isEmpty()) return
    val showElevation = splits.any { it.elevationGainMeters != null }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
            SplitHeaderRow(showElevation = showElevation)
            Spacer(modifier = Modifier.height(8.dp))
            splits.forEach { split ->
                SplitRow(split = split, showElevation = showElevation)
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun SplitHeaderRow(showElevation: Boolean) {
    val style = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    // Read out as a whole by the row beneath it, so a screen reader is not made to announce four
    // column headings before every kilometre of a long run.
    Row(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("KM", modifier = Modifier.width(KmColumnWidth), style = style, color = color)
        Text("PACE", modifier = Modifier.width(PaceColumnWidth), style = style, color = color)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            "HR",
            modifier = Modifier.width(HrColumnWidth),
            style = style,
            color = color,
            textAlign = TextAlign.End
        )
        if (showElevation) {
            Text(
                "ELEV",
                modifier = Modifier.width(ElevationColumnWidth),
                style = style,
                color = color,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun SplitRow(split: Split, showElevation: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = split.spokenDescription(showElevation) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = split.label,
            modifier = Modifier.width(KmColumnWidth),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = formatMinutesPerKm(split.paceMinPerKm),
            modifier = Modifier.width(PaceColumnWidth),
            style = MaterialTheme.typography.bodyMedium
        )
        RelativePaceBar(split = split, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Text(
            text = split.averageBpm?.toString() ?: "--",
            modifier = Modifier.width(HrColumnWidth),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End
        )
        if (showElevation) {
            Text(
                text = split.elevationGainMeters?.let { "${it.roundToInt()} m" } ?: "--",
                modifier = Modifier.width(ElevationColumnWidth),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun RelativePaceBar(split: Split, modifier: Modifier = Modifier) {
    Box(modifier = modifier.height(BarHeight)) {
        if (split.relativePace <= 0.0) return@Box
        Box(
            modifier = Modifier
                // Never quite nothing: the fastest kilometre of a run with one big blow-up in it can
                // scale to a couple of pixels, and a row with no bar at all reads as missing data
                // rather than as the fastest split on the page.
                .fillMaxWidth(split.relativePace.toFloat().coerceIn(SHORTEST_VISIBLE_BAR, 1f))
                .height(BarHeight)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

private val KmColumnWidth = 40.dp
private val PaceColumnWidth = 56.dp
private val HrColumnWidth = 40.dp
private val ElevationColumnWidth = 48.dp

/** How far this split went, in kilometres — "0.40" for a final stretch of 400 m. */
private val Split.kilometresText: String
    get() = "%.2f".format(distanceMeters / SPLIT_METERS)

/**
 * The number in the KM column: the kilometre's own number, or — for a final stretch the runner did
 * not finish — how far that stretch actually went, which is what makes it read as a part rather than
 * as a whole.
 */
private val Split.label: String
    get() = if (isPartial) kilometresText else "$number"

/** The row as a sentence, so the bar (which says nothing out loud) costs a screen reader nothing. */
private fun Split.spokenDescription(showElevation: Boolean): String = buildString {
    append(if (isPartial) "Final $kilometresText kilometres" else "Kilometre $number")
    append(", ")
    val pace = formatMinutesPerKm(paceMinPerKm)
    append(if (pace == "--:--") "no pace" else "$pace per kilometre")
    averageBpm?.let { append(", $it beats per minute") }
    if (showElevation) elevationGainMeters?.let { append(", ${it.roundToInt()} metres climbed") }
}
