package com.example.runningapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.runningapp.ui.theme.RunningUiTokens
import com.example.runningapp.ui.workout.TodayCardWorkout
import com.example.runningapp.ui.workout.TodayCardLinkKind
import com.example.runningapp.ui.workout.TodayCardUiState

@Composable
fun TodayCard(
    state: TodayCardUiState,
    onPickWorkout: (String) -> Unit,
    onSkipToday: () -> Unit,
    onUndoSkip: () -> Unit,
    onChoosePlan: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
            Text(
                text = state.eyebrow,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = state.detailLine, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            TargetPill(text = state.targetPill)
            state.envelopeLine?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Above the coach's note and below the shape, because it is an instruction for the run
            // about to be started rather than a remark about the last one (#291).
            state.instructionLine?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            state.coachNote?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            // Directly above the rows it points at (#292), so "pick it below" names something the
            // eye lands on next — and below the card's own workout, because the Test is an offer
            // for another day and never a correction to the Run about to be started.
            state.testDueLine?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (state.workouts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "TODAY'S RUN",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                state.workouts.forEach { workout ->
                    WorkoutRow(workout = workout, onPick = { onPickWorkout(workout.workoutId) })
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.link.label,
                    style = MaterialTheme.typography.bodySmall,
                    textDecoration = TextDecoration.Underline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .clickable(role = Role.Button) {
                            when (state.link.kind) {
                                TodayCardLinkKind.SKIP -> onSkipToday()
                                TodayCardLinkKind.UNDO -> onUndoSkip()
                                TodayCardLinkKind.CHOOSE_PLAN -> onChoosePlan()
                            }
                        }
                        // The link stays small type by design, so the tap target is grown to the
                        // shared minimum around it rather than the text being made into a button.
                        .heightIn(min = RunningUiTokens.MinTouchTarget)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 4.dp)
                )
            }
        }
    }
}

/**
 * One of the Stage's Workouts, offered as today's Run (#174).
 *
 * A radio, not a button: the Pick is one of three, and the two not Picked stay on the screen as
 * what they are — still offered, not dismissed.
 */
@Composable
private fun WorkoutRow(workout: TodayCardWorkout, onPick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (workout.picked) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = workout.picked, role = Role.RadioButton, onClick = onPick)
            .heightIn(min = RunningUiTokens.MinTouchTarget)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = "${workout.runTypeLabel.uppercase()} · ${workout.title}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (workout.picked) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = workout.summaryLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TargetPill(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
