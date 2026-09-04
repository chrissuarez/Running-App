package com.example.runningapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * Every Run the runner has made over one route, newest first (#73).
 *
 * The list the card on a Run's page is the door to. Newest first here and oldest first on the chart,
 * which is not a disagreement: a chart is a history and reads left to right through it, and a list
 * is something to look through, where what happened last is what a runner is looking for.
 *
 * The Run whose page this was opened from is marked rather than left out. It is one of the group,
 * and a list with a hole in it would have the runner counting to work out which line is missing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchedRunsScreen(
    /** Null while the shapes are still being read, and once the Run this was opened from is gone. */
    matched: MatchedRunsUi?,
    onOpenRun: (Long) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(matchedRunsListTitle(matched?.courseName)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (matched == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        // Sorted once per group rather than on every recomposition, the way the card remembers its
        // trend: newest first here and oldest first on the chart is not a disagreement — a chart is
        // a history and reads left to right, and a list is where a runner looks for what happened
        // last.
        val newestFirst = remember(matched.runs) { matched.runs.sortedByDescending { it.startTime } }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(RunningUiTokens.PagePadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = matchedRunCountLabel(matched.count, matched.courseName),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(newestFirst, key = { it.sessionId }) { run ->
                MatchedRunRow(run = run, onOpen = { onOpenRun(run.sessionId) })
            }
        }
    }
}

/** One Run of the group: when it was, how far it went, how long it took, and how quick that was. */
@Composable
private fun MatchedRunRow(run: MatchedRunUi, onOpen: () -> Unit) {
    val thisOne = if (run.isThisRun) ", the run you are looking at" else ""
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RunningUiTokens.MinTouchTarget)
                .clickable(onClick = onOpen)
                .semantics {
                    contentDescription =
                        "${run.dateLabel}, ${run.distanceLabel}, ${run.timeLabel}, ${run.paceLabel}$thisOne"
                }
                .padding(RunningUiTokens.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = run.dateLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (run.isThisRun) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    text = "${run.distanceLabel} · ${run.paceLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = run.timeLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
