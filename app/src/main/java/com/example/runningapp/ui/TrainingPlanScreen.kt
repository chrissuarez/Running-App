package com.example.runningapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.PlanStage
import com.example.runningapp.TrainingPlanProvider
import com.example.runningapp.lockedStageIds
import com.example.runningapp.training.PlanCompletion
import com.example.runningapp.training.planCompleteLine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingPlanScreen(
    activePlanId: String?,
    activeStageId: String?,
    /**
     * What the active Stage says about a bar the runner has already beaten in history (#293), or
     * null when they have not — see [com.example.runningapp.training.alreadyBeatenLine]. Shown on
     * the active Stage alone: a locked Stage's bar is not one the runner is being asked to clear
     * yet, and a Stage already past is not one they are staring at wondering why it did not count.
     */
    alreadyBeatenLine: String?,
    /**
     * The Plan the runner has finished, if they have finished one (#294) — the whole of what the
     * completed Stage's card says, and the only place this screen learns that anything is complete.
     * It asks history nothing.
     */
    planCompletion: PlanCompletion?,
    onActivatePlan: (planId: String, stageId: String) -> Unit,
    onBack: () -> Unit
) {
    val plans = TrainingPlanProvider.getAllPlans()
    if (plans.isEmpty()) return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Training Plan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(plans, key = { _, plan -> plan.id }) { index, plan ->
                val isPlanActive = activePlanId == plan.id
                val firstStageId = plan.stages.firstOrNull()?.id
                val selectedStageId = if (isPlanActive) activeStageId ?: firstStageId else null

                Column {
                    Text(
                        text = plan.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = plan.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!isPlanActive && firstStageId != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { onActivatePlan(plan.id, firstStageId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Activate Plan")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    // Which Stage of this plan, if any, is the one the runner finished: the last
                    // one, and only where the stored completion is about this plan. Keyed by plan
                    // id so that a completion never claims a different plan is over.
                    val completion = planCompletion?.takeIf { it.planId == plan.id }
                    val completedStageId =
                        if (completion == null) null else plan.stages.lastOrNull()?.id
                    // Locked-ness is the Stage's position against the one the runner is in, asked
                    // of the same id the ACTIVE badge is decided by (#301) — so the padlock lands
                    // ahead of the runner and never on them.
                    val lockedStageIds = plan.lockedStageIds(selectedStageId)
                    plan.stages.forEach { stage ->
                        val isActiveStage = stage.id == selectedStageId
                        // The whole sentence, built here from the stored completion and this
                        // Stage's own Requirement — null on every other Stage and on a plan nobody
                        // has finished. Null where a completed Stage somehow carries no Requirement
                        // in numbers, which cannot arise from the rule that writes one: a
                        // completion is granted by that Requirement being answered.
                        val completedLine = if (completion == null || stage.id != completedStageId) {
                            null
                        } else {
                            stage.bestEffortRequirement?.let { planCompleteLine(completion, it) }
                        }
                        StageCard(
                            stage = stage,
                            isActive = isActiveStage,
                            isLocked = stage.id in lockedStageIds,
                            // Suppressed once the plan is complete (#293, #294). "Run one now and it
                            // counts" is an offer the rule will not honour any more — the one thing
                            // that line may never be.
                            alreadyBeatenLine = alreadyBeatenLine
                                .takeIf { isActiveStage && completedLine == null },
                            completedLine = completedLine
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (index != plans.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StageCard(
    stage: PlanStage,
    isActive: Boolean,
    /**
     * Whether this Stage is one the runner has not reached — later in the Plan than the Stage they
     * are in ([com.example.runningapp.lockedStageIds]). Never true of the Stage they are in, so the
     * padlock and the ACTIVE badge can no longer both be candidates for the same card (#301).
     */
    isLocked: Boolean,
    alreadyBeatenLine: String?,
    /**
     * What this Stage's Requirement has become, on the Stage that finished the plan (#294): the fact
     * that it was met, on a day, in a time. Null on every Stage the runner has not finished a plan
     * on — which is every card but one, and all of them until they do.
     *
     * Non-null is the whole of "this Stage is complete" as far as the card is concerned: the badge,
     * the replaced Requirement line, and the suppressed already-beaten line are one state, not three
     * flags that could disagree.
     */
    completedLine: String?
) {
    val isComplete = completedLine != null
    val cardColor = when {
        // Still ahead of the locked branch, and so is the badge below. The runner's own Stage can no
        // longer be locked (#301), but the plan they finished stays theirs on any reading: a card
        // showing a padlock over the sentence saying it is finished would be the screen
        // contradicting itself.
        isComplete || isActive -> MaterialTheme.colorScheme.primaryContainer
        isLocked -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isLocked && !isComplete) 0.72f else 1f),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Given the room and made to wrap, because at 320dp with large text the title ran
                // straight into the padlock beside it (#301). The badge takes what it needs; the
                // title takes the rest and folds onto a second line rather than touching it.
                Text(
                    text = stage.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = 8.dp)
                )

                when {
                    isComplete -> {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "COMPLETE",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    // The runner's own Stage before the padlock (#301).
                    isActive -> {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "ACTIVE",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    isLocked -> {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked stage",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stage.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // On the Stage that finished the plan the Requirement is gone and the fact
                    // stands in its place (#294) — with no "Graduation Requirement:" label over it,
                    // because there is nothing left here to graduate and the sentence names itself.
                    // Leaving the bar printed as something to achieve, beside a congratulation
                    // saying it was achieved, is the whole of what this ticket is about.
                    if (completedLine != null) {
                        Text(
                            text = completedLine,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            text = "Graduation Requirement:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stage.graduationRequirementText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    // Directly under the bar it is about, because it is a remark on that sentence
                    // and not a second thing the Stage asks for. It states a fact and offers
                    // nothing (#293), so it is text and never a control: there is nothing here to
                    // tap, because there is nothing here the app is about to grant.
                    if (alreadyBeatenLine != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = alreadyBeatenLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
