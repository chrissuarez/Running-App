package com.example.runningapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * Everything one Record holds (#75): the all-time top ten, and how the runner's best at it has moved
 * across the calendar.
 *
 * Ten deep rather than three, which is the whole reason the efforts are banked
 * ([com.example.runningapp.data.RunEffortRow]) — the record book keeps three, and fourth to tenth
 * place is exactly what a runner comparing themselves against themselves came here to see.
 *
 * The chart sits above the list because the list is what the page is *for*: the runner scrolls into
 * it and stays there, and a chart under fifty rows is a chart nobody ever meets. Both are one
 * reading of one set of rows, so the gold at the top and the first point on the line cannot disagree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordDetailScreen(
    detail: RecordDetailUi,
    onOpenRun: (Long) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail.type.label) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (detail.top.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(RunningUiTokens.PagePadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // Two different empty pages, and telling them apart matters (#75): a Record
                    // nobody has contested is told what would take it, but a Record whose claims
                    // have not been read back yet must not be — a runner who has run this distance
                    // a hundred times would be told they never had. While history is being
                    // measured, the page says that instead.
                    text = if (detail.measuring) RECORDS_MEASURING_MESSAGE
                    else recordEmptyMessage(detail.type),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(RunningUiTokens.PagePadding),
            verticalArrangement = Arrangement.spacedBy(RunningUiTokens.SectionSpacing),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
                        Text(
                            text = "All-time best",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = detail.top.first().effort.valueLabel,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = recordEffortCountLabel(detail.effortCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (detail.trend.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
                            Text(
                                text = "Best by day",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Your best on each day you contested it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TrendLineChart(
                                points = detail.trend.map {
                                    TrendChartPoint(it.dayOffset, it.value.toFloat())
                                },
                                firstDay = detail.trend.first().date,
                                valueLabel = { recordTrendValueLabel(detail.type, it) },
                                spoken = recordTrendDescription(detail.type, detail.trend).orEmpty(),
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = recordTopTitle(detail.effortCount),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(detail.top, key = { it.effort.sessionId }) { placed ->
                RecordRankedEffortRow(placed = placed, onOpen = { onOpenRun(placed.effort.sessionId) })
            }
        }
    }
}

/**
 * One effort in the ranked list, drawn as every league table of the runner's efforts is
 * ([RankedEffortRow]) — the same discs a Segment's page and a Run's own page hand out (#71), because
 * a place is a place and a runner should not have to learn two of them.
 */
@Composable
private fun RecordRankedEffortRow(placed: RecordRankedEffortUi, onOpen: () -> Unit) {
    val effort = placed.effort
    RankedEffortRow(
        place = placed.place,
        medal = placed.medal,
        primary = effort.dateLabel,
        // Null at the two totals, which have no pace of their own ([RecordEffortUi.paceLabel]).
        secondary = effort.paceLabel,
        trailing = effort.valueLabel,
        spoken = spokenPlace(placed.place, placed.medal) +
            "${effort.dateLabel}, ${effort.valueLabel}" + effort.paceLabel?.let { ", $it" }.orEmpty(),
        onOpen = onOpen,
    )
}

/** One Record's whole page, as the view model hands it over. */
data class RecordDetailUi(
    val type: RecordType,
    val top: List<RecordRankedEffortUi>,
    val trend: List<RecordTrendPoint>,
    /** How many Runs have ever contested it, which is what says whether the ten is the whole list. */
    val effortCount: Int,
    /**
     * Whether history is still being measured against the book, in which case the three above are
     * deliberately empty and the page says so instead (#75).
     *
     * Defaulted false, because every caller but the view model is a screen preview or a test that
     * hands over the finished article — and a page built from real rows is by definition not one
     * built from a table that is still filling.
     */
    val measuring: Boolean = false,
)
