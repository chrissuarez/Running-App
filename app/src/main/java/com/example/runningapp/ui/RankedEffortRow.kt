package com.example.runningapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.Medal
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * One placed effort in a league table of the runner's own efforts.
 *
 * Two pages rank the same runner's efforts against each other — a Segment's times (#70, #72) and a
 * Record's (#75) — and a runner must not have to learn two league tables. So the row is written once
 * rather than twice with a promise in the comments: the same discs, the same hole where a disc would
 * be below third, the same date and detail stacked under each other so the row survives a narrow
 * phone at a large text size (#63, #232).
 *
 * The whole row is a door to the Run the effort was part of. A time is not the whole story of the
 * morning it was run, and the page the runner would go looking for it on is the Run's own.
 */
@Composable
internal fun RankedEffortRow(
    place: Int,
    /** The top three, or null below them — how deep the metals go is [Medal]'s own answer. */
    medal: Medal?,
    /** The line the list is scanned down: the day it was run. */
    primary: String,
    /** What that day is worth saying underneath — a pace, or nothing where the Record has none. */
    secondary: String?,
    /** The number the runner came to the list for, on its own at the end. */
    trailing: String,
    /** What the row says out loud, which its caller words in its own page's terms. */
    spoken: String,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RunningUiTokens.MinTouchTarget)
            .clickable(onClick = onOpen)
            .semantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (medal != null) {
            MedalDisc(medal)
        } else {
            // The same width the discs take, so the dates line up down the list instead of
            // stepping in at fourth place.
            Box(
                modifier = Modifier
                    .size(RunningUiTokens.MedalDiscSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$place",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = primary, style = MaterialTheme.typography.bodyLarge)
            secondary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = trailing,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** How a placed effort names its place out loud: its metal, or the number it came in at. */
internal fun spokenPlace(place: Int, medal: Medal?): String =
    medal?.let { "${it.face.spoken}, " } ?: "Number $place, "
