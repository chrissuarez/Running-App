package com.example.runningapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * The named ground this Run went over, and what it took there (#71).
 *
 * Draws nothing when the Run crossed no Segment, which is most Runs for most runners — the same
 * bargain [AchievementsCard] makes. A card that appeared on every page to say "no segments" would
 * be a section that exists to say no.
 *
 * Sits beside the medals rather than among them because the two answer different questions. The
 * achievements card is about the Run as a whole against the record book; this is about places the
 * runner keeps coming back to, and a row of it is a door to that place's own page.
 */
@Composable
fun RunSegmentsCard(
    efforts: List<RunSegmentEffortUi>,
    onOpenSegment: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (efforts.isEmpty()) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
            efforts.forEachIndexed { index, effort ->
                if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                RunSegmentLine(effort = effort, onOpen = { onOpenSegment(effort.segmentId) })
            }
        }
    }
}

/**
 * One crossing: its medal if it took one, the name of the place, how quick it was, and the time.
 *
 * The whole row is the target rather than the name alone, so it can be hit after a run with cold
 * hands, and it is held to the app's minimum touch height for the same reason.
 *
 * A row with no medal still leaves the disc's width empty, so the names line up down the card
 * instead of stepping in and out as the medals come and go.
 */
@Composable
private fun RunSegmentLine(effort: RunSegmentEffortUi, onOpen: () -> Unit) {
    val spokenMedal = effort.medal?.let { "${it.face.spoken}, " } ?: ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RunningUiTokens.MinTouchTarget)
            .clickable(onClick = onOpen)
            .semantics {
                contentDescription =
                    "$spokenMedal${effort.segmentName}, ${effort.timeLabel}, ${effort.paceLabel}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (effort.medal != null) {
            MedalDisc(effort.medal)
        } else {
            Spacer(modifier = Modifier.size(RunningUiTokens.MedalDiscSize))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = effort.segmentName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = effort.paceLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = effort.timeLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
