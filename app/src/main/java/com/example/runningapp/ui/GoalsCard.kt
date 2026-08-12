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
import androidx.compose.runtime.LaunchedEffect
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
import kotlin.math.floor
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
 *
 * A Runs target is a whole number as well, because a Run only ever adds a whole one: two and a bit
 * runs is a target no week can land on, and the card would round it to "2 / 2 runs" without ever
 * ticking it. Refused rather than rounded, exactly as an empty or a zero target is — the runner
 * gets their typing back to correct, not a number they did not type.
 */
fun goalTargetOf(metric: GoalMetric, typed: String): Double? {
    val number = typed.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (!number.isFinite() || number <= 0.0) return null
    if (metric == GoalMetric.COUNT && number != floor(number)) return null
    return number
}

/** What to say under the field when what is typed there is not a target of this metric. */
fun goalTargetHintOf(metric: GoalMetric): String = when (metric) {
    GoalMetric.COUNT -> "Enter a whole number of runs, like 3"
    else -> "Enter a number greater than zero, like 40"
}

/**
 * What the target field reads when the runner has just chosen a period and a metric (#82).
 *
 * A period and a metric together name one goal, so choosing a pair is asking about that goal: the
 * field shows the target it already stands at, and is empty where there is nothing standing. Left to
 * itself the field kept whatever was last typed, which meant a 40 typed against a distance goal sat
 * there under a heading saying "Change this goal" when the runner switched to Time — and Save would
 * have taken them at their word and written 40 hours a week.
 */
fun goalFieldOf(standing: Goal?): String =
    if (standing == null) "" else goalAmountText(standing.metric, standing.target)

/**
 * When the field is refilled from the goal the chips name (#82).
 *
 * Keyed on the period and the metric alone, the field only followed a chip: removing the goal the
 * chips named left its target sitting in the field with Save live, so a runner who deleted a goal
 * and then pressed Save wrote it straight back, and saving a brand-new goal left the field empty
 * under a heading that had just changed to "Change this goal".
 *
 * So the field is refilled whenever the text it *should* read changes — that is what this key is,
 * the pair plus the standing target as the field would write it. A chip, a save and a delete all
 * move it, and nothing else does: typing is not in it, so a runner correcting 40 to 45 on the pair
 * they are already on is never interrupted, and refilling the field cannot move the key that
 * refilled it.
 */
fun goalFieldKeyOf(period: GoalPeriod, metric: GoalMetric, standing: Goal?): String =
    "${period.name}/${metric.name}/${goalFieldOf(standing)}"

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
    val target = goalTargetOf(metric, typed)
    val rejected = typed.isNotBlank() && target == null

    // The field follows the goal the chips name, however it moved — a chip, a save, or a bin. What
    // it does not follow is the runner's own typing, so correcting a target is never interrupted.
    LaunchedEffect(goalFieldKeyOf(period, metric, standing)) { typed = goalFieldOf(standing) }

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
                                typed = goalFieldOf(progress.goal)
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
                    { Text(goalTargetHintOf(metric)) }
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
                    // Saving does not empty the field: the goal is still there and the heading still
                    // says so, so the field goes on reading it. Blanking it here would also outlast
                    // saving a target the pair already stood at, which changes nothing to refill it.
                    onClick = { target?.let { onSet(period, metric, it) } },
                    enabled = target != null,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Save")
                }
            }
        }
    }
}
