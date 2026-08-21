package com.example.runningapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * The Records section of the Progress screen (#75): the all-time best at each of the seven Records,
 * and a door into each one's own page.
 *
 * Every Record whether it has been run or not ([recordSlots]). An empty slot is a thing to aim at,
 * and a grid that hid the distances the runner has not reached would quietly shrink as they took
 * their first steps at each — the opposite of what a record book is for.
 *
 * Two to a row rather than a free-flowing grid, so the pairs line up down the page at every width
 * the app supports: at 320dp with the system text turned up, a cell is about 136dp and the longer
 * names wrap inside their own half rather than pushing a neighbour off the screen (#63, #232).
 */
@Composable
fun RecordsCard(
    slots: List<RecordSlotUi>,
    onOpenRecord: (RecordType) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Nothing at all until the rows have been read, rather than seven empty slots: [recordSlots]
    // always hands back all seven, so an empty list is the moment before the first read and not a
    // runner with no records. A grid that filled in a beat later would read as a record lost.
    if (slots.isEmpty()) return
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RunningUiTokens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Records",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            slots.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pair.forEach { slot ->
                        RecordSlot(
                            slot = slot,
                            onOpen = { onOpenRecord(slot.type) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // The last row of an odd grid keeps its cell half the width of the others,
                    // rather than letting one lonely record stretch across the page.
                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One Record in the grid: what it is, and the best ever done at it.
 *
 * A slot nobody has contested says so in the same place the number would be, so the grid keeps its
 * shape. The whole cell is the door, and it is a door either way — an empty Record's page is where
 * the runner is told what would take it.
 */
@Composable
private fun RecordSlot(slot: RecordSlotUi, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val best = slot.best
    val spoken = if (best == null) "${slot.type.label}, not run yet"
    else "${slot.type.label}, ${best.valueLabel}, ${best.dateLabel}"
    Column(
        modifier = modifier
            .heightIn(min = RunningUiTokens.MinTouchTarget)
            .clickable(onClick = onOpen)
            .semantics { contentDescription = spoken },
    ) {
        Text(
            text = slot.type.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = best?.valueLabel ?: EMPTY_RECORD_VALUE,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (best == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = best?.dateLabel ?: "Not run yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What stands where the number would be at a Record nobody has contested.
 *
 * A dash rather than a zero or a blank: a zero is a claim that somebody ran it in no time at all,
 * and a blank collapses the cell so the grid stops lining up.
 */
private const val EMPTY_RECORD_VALUE = "—"
