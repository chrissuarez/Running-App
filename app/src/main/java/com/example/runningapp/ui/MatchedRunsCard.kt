package com.example.runningapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * The other times the runner has run this route, and whether they are getting quicker on it (#73).
 *
 * Draws nothing for a Run nobody has repeated, which is the [RunSegmentsCard] bargain: a card that
 * appeared on every page to say "your 1st run on this route" would be a section that exists to say
 * nothing. The card arrives the day a route becomes a route — the second time it is run.
 *
 * The headline is the count, because the count is the news. The chart under it is the reason the
 * count is worth having: repetition on its own is a habit, and repetition with a pace line through
 * it is progress.
 *
 * The whole card is the door to the list, and it is held to the app's minimum touch height for the
 * reason every row on this page is — it is tapped after a run, with cold hands.
 */
@Composable
fun MatchedRunsCard(
    matched: MatchedRunsUi,
    onOpenMatchedRuns: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trend = remember(matched.runs) { matchedRunTrendPoints(matched.runs) }
    val headline = matchedRunHeadline(matched.position)
    val count = matchedRunCountLabel(matched.count)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RunningUiTokens.MinTouchTarget)
                .clickable(onClick = onOpenMatchedRuns)
                .semantics { contentDescription = "$headline, $count" }
                .padding(RunningUiTokens.CardPadding)
        ) {
            Text(
                text = headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = count,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (trend.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = MATCHED_RUNS_TREND_SUBTITLE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                TrendLineChart(
                    points = trend.map {
                        TrendChartPoint(dayOffset = it.dayOffset, value = it.paceMinPerKm.toFloat())
                    },
                    firstDay = trend.first().date,
                    valueLabel = { matchedRunPaceAxisLabel(it) },
                    spoken = matchedRunTrendDescription(trend).orEmpty(),
                )
            }
        }
    }
}
