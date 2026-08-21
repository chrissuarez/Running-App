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
        val top = detail.top
        if (top.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(RunningUiTokens.PagePadding),
                contentAlignment = Alignment.Center,
            ) {
                // Three different bare pages, and telling them apart matters (#75) — see
                // [recordDetailMessage], which is where the choosing lives. The third of them says
                // nothing at all, which is why this is a message that can be absent rather than a
                // string: a page whose rows have not come back from Room yet must stay silent until
                // they do, rather than telling a runner who has covered this distance a hundred
                // times that they never have.
                recordDetailMessage(detail)?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                            text = top.first().effort.valueLabel,
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

            items(top, key = { it.effort.sessionId }) { placed ->
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
    /**
     * The ranked efforts, best first — or null, which is not the same thing as none of them (#75).
     *
     * Null is "Room has not answered yet", and it is the state the page opens in: a screen is drawn
     * the instant the runner taps a cell in the grid, and the first read of `run_efforts` lands a
     * frame or several frames later on a long history. An empty list is Room's answer, and it means
     * the runner really has never contested this Record.
     *
     * Kept as the absence of the rows themselves rather than as a second flag beside them, because
     * a flag saying "read yet" would be a second answer to a question the rows already answer, and
     * two answers can disagree. [measuring] is a different fact again: there the read *has*
     * answered, and the answer is deliberately nothing because the answer it could give would be
     * read off a table that is still filling.
     */
    val top: List<RecordRankedEffortUi>?,
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

/**
 * What a Record's page says when it has no ranked efforts to draw — or nothing at all (#75).
 *
 * Three states arrive here looking alike and the runner is owed a different thing by each:
 *
 * - the rows have not come back from Room yet ([RecordDetailUi.top] null), and the page must say
 *   *nothing*. This is the frame or two right after the runner taps a populated cell in the grid,
 *   and telling them "you have not covered 5 km" there would flatly contradict the number they just
 *   tapped. Silence for a beat is a page still opening; a wrong sentence is a record lost.
 * - history is being measured against the book wholesale ([RecordDetailUi.measuring]), which lasts
 *   minutes and is worth saying out loud — the grid says the same thing in the same words.
 * - the rows came back and there are none, which is a real empty Record and gets told what would
 *   take it ([recordEmptyMessage]).
 *
 * A function rather than three branches inside the screen, so the choosing can be tested without
 * drawing anything — it is a rule about truthfulness, not about layout.
 */
fun recordDetailMessage(detail: RecordDetailUi): String? = when {
    detail.top == null -> null
    detail.measuring -> RECORDS_MEASURING_MESSAGE
    detail.top.isEmpty() -> recordEmptyMessage(detail.type)
    else -> null
}

/**
 * The page the moment it opens, before Room has answered (#75).
 *
 * Named rather than written out at the call site, so that "not read back yet" is one thing with one
 * spelling: a caller left to build the initial value by hand is a caller one `emptyList()` away
 * from the bug this closes.
 */
fun recordDetailNotReadYet(type: RecordType): RecordDetailUi =
    RecordDetailUi(type = type, top = null, trend = emptyList(), effortCount = 0)
