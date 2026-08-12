package com.example.runningapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.runningapp.training.Goal
import com.example.runningapp.training.GoalMetric
import com.example.runningapp.training.GoalPeriod
import com.example.runningapp.training.GoalProgress
import kotlin.math.roundToInt

/**
 * A goal's number as it is written on the bar — whole where it is whole, and to a tenth where it is
 * not.
 *
 * Runs are counted and never fractional. Kilometres and hours are, but only just: "24.0 / 40 km" is
 * a precision nobody asked for, and "23.7" is one they can see the point of.
 */
fun goalAmountText(metric: GoalMetric, amount: Double): String {
    if (metric == GoalMetric.COUNT) return amount.roundToInt().toString()
    val rounded = (amount * 10).roundToInt() / 10.0
    return if (rounded == rounded.roundToInt().toDouble()) {
        rounded.roundToInt().toString()
    } else {
        rounded.toString()
    }
}

/**
 * What a goal has typed into it, as a target — null when it is not one (#82).
 *
 * The same rule and the same comma as a Stated Distance ([statedDistanceKmOf]): a target is a
 * positive, finite number in the metric's own unit, and nothing here caps it. A hundred hours a week
 * is not a week anyone has, but neither is it the app's to refuse — a target is the runner's to set
 * and theirs to correct.
 */
fun goalTargetOf(typed: String): Double? {
    val number = typed.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return number.takeIf { it.isFinite() && it > 0.0 }
}

/** One goal, said in full: "This week — 24 / 40 km". */
fun goalLineOf(progress: GoalProgress): String {
    val done = goalAmountText(progress.goal.metric, progress.done)
    val target = goalAmountText(progress.goal.metric, progress.goal.target)
    return "${progress.goal.period.thisPeriod} — $done / $target ${progress.goal.metric.unit}"
}

/**
 * The runner's goals and how full each one is (#82), at the top of the Progress screen.
 *
 * A met goal fills its bar and gets a tick, and that is the whole of the celebration: goals are set
 * quietly and met quietly. Nothing here pops up, sounds, or is spoken — a runner meeting a weekly
 * target mid-Run must not be told about it by a coach whose job is the Run in front of them.
 *
 * The card is one tap to the manage view whether or not there are goals in it, so "Set a goal" and
 * "change these goals" are the same gesture rather than two.
 */
@Composable
fun GoalsCard(goals: List<GoalProgress>, onManage: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onManage)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "Goals", style = MaterialTheme.typography.titleMedium)
            if (goals.isEmpty()) {
                Text(
                    text = "No goals set. Set one and it renews every week, month or year.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onManage) { Text("Set a goal") }
            } else {
                goals.forEach { GoalBar(it) }
            }
        }
    }
}

/** One labelled bar: what the goal is, how far in the runner is, and a tick once it is met. */
@Composable
private fun GoalBar(progress: GoalProgress) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // One line for the reader, rather than a label, a bar and a tick read out separately.
            .clearAndSetSemantics {
                contentDescription = goalLineOf(progress) +
                    if (progress.met) ", met" else ""
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = goalLineOf(progress), style = MaterialTheme.typography.bodyMedium)
            if (progress.met) {
                Icon(Icons.Default.Check, contentDescription = null)
            }
        }
        LinearProgressIndicator(
            progress = progress.fraction.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Where goals are added, changed and removed (#82).
 *
 * One editor rather than an add screen and an edit screen: a period and a metric together name one
 * goal, so choosing a pair the runner already has puts its target in the field and saving overwrites
 * it. That is the same act to them, and the table enforces it either way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsSheet(
    goals: List<GoalProgress>,
    onSet: (GoalPeriod, GoalMetric, Double) -> Unit,
    onRemove: (Goal) -> Unit,
    onDismiss: () -> Unit,
) {
    // Fully expanded, never resting half-height: the field here summons the keyboard, and a
    // partially-expanded sheet puts its keyboard padding off the bottom of the screen (#238).
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var period by remember { mutableStateOf(GoalPeriod.WEEK) }
    var metric by remember { mutableStateOf(GoalMetric.DISTANCE) }
    var typed by remember { mutableStateOf("") }

    val standing = goals.firstOrNull { it.goal.period == period && it.goal.metric == metric }?.goal
    val target = goalTargetOf(typed)
    val rejected = typed.isNotBlank() && target == null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Goals", style = MaterialTheme.typography.titleLarge)
            Text(
                "A goal recurs: set 40 km a week and every week is measured against it, until you " +
                    "change it or remove it.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (goals.isNotEmpty()) {
                goals.forEach { progress ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${progress.goal.period.label} ${progress.goal.metric.label}: " +
                                "${goalAmountText(progress.goal.metric, progress.goal.target)} " +
                                progress.goal.metric.unit,
                            style = MaterialTheme.typography.bodyMedium,
                            // Wraps rather than pushing Edit and the bin off the row: on a narrow
                            // screen at large text the longest label was squeezing the remove
                            // button down to a sliver, leaving a goal with no visible way out.
                            modifier = Modifier.weight(1f),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                // Puts the goal in the editor below rather than opening a second
                                // one: editing and setting are the same act.
                                period = progress.goal.period
                                metric = progress.goal.metric
                                typed = goalAmountText(progress.goal.metric, progress.goal.target)
                            }) { Text("Edit") }
                            IconButton(onClick = { onRemove(progress.goal) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove the " +
                                        "${progress.goal.period.label} " +
                                        "${progress.goal.metric.label} goal",
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = if (standing == null) "Set a goal" else "Change this goal",
                style = MaterialTheme.typography.titleMedium,
            )
            ChipPicker(
                options = GoalPeriod.entries,
                selected = period,
                labelOf = { it.label },
                onChosen = { period = it },
            )
            ChipPicker(
                options = GoalMetric.entries,
                selected = metric,
                labelOf = { it.label },
                onChosen = { metric = it },
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("Target (${metric.unit})") },
                singleLine = true,
                isError = rejected,
                supportingText = if (rejected) {
                    { Text("Enter a number greater than zero, like 40") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Done") }
                Button(
                    onClick = {
                        target?.let { onSet(period, metric, it) }
                        typed = ""
                    },
                    enabled = target != null,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Save")
                }
            }
        }
    }
}
