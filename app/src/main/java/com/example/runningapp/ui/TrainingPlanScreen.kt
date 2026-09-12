package com.example.runningapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.PlanStage
import com.example.runningapp.TrainingPlanProvider
import com.example.runningapp.lockedStageIds
import com.example.runningapp.passedStageIds
import com.example.runningapp.training.PlanCompletion
import com.example.runningapp.training.StageTrainingSummary
import com.example.runningapp.training.StageWeek
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
     * What the app has already counted about the active Stage's training (#445), or null while it
     * has not been read yet.
     *
     * Shown on the active Stage alone, for the same reason [alreadyBeatenLine] is: a locked Stage
     * has no training behind it to count, and a Stage already past is not one the runner is
     * standing in wondering where they stand.
     */
    stageTraining: StageTrainingSummary?,
    /**
     * What the active Stage says about a bar written as a time the runner has not beaten yet
     * (#446), or null where they have beaten it — see
     * [com.example.runningapp.training.barShortfallLine]. Exclusive with [alreadyBeatenLine] by
     * construction: both are answers to one comparison of one best effort.
     *
     * Shown on the active Stage alone. A shortfall against a Stage the runner has not reached is
     * the app measuring them against a bar they have not been set, and Stage 3's bar shown as a gap
     * while they are in Stage 1 is discouragement with no purpose.
     */
    barShortfallLine: String?,
    /**
     * The Plan the runner has finished, if they have finished one (#294) — the whole of what the
     * completed Stage's card says, and the only place this screen learns that anything is complete.
     * It asks history nothing.
     */
    planCompletion: PlanCompletion?,
    onActivatePlan: (planId: String, stageId: String) -> Unit,
    /**
     * The runner putting themselves back on a Stage they have already left (#235) — offered on
     * those Stages and nowhere else, and never forwards: moving forward by hand would hand out a
     * graduation nobody earned.
     */
    onMoveBackToStage: (planId: String, stageId: String, stageTitle: String) -> Unit,
    onBack: () -> Unit
) {
    val plans = TrainingPlanProvider.getAllPlans()
    if (plans.isEmpty()) return

    // The Stage the runner has asked to go back to and not yet confirmed. One slot for the whole
    // screen, because one dialog is open at a time; null is "no dialog". Held here rather than in
    // the card so that the card the dialog is about can scroll away without taking the question
    // with it.
    var pendingMoveBack by remember { mutableStateOf<PendingMoveBack?>(null) }

    pendingMoveBack?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingMoveBack = null },
            title = { Text("Go back to ${pending.stageTitle}?") },
            // Says what the move costs before it is made, because the two things a runner would
            // fear are the two things worth stating: their history is not touched, and the coach's
            // queued workout is. Written as plainly as the card above it.
            text = {
                Text(
                    "You'll train this stage again. Your runs, records and best efforts are not " +
                        "changed. The coach's next-run suggestions are cleared, and it will make " +
                        "new ones after your next run."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onMoveBackToStage(pending.planId, pending.stageId, pending.stageTitle)
                    pendingMoveBack = null
                }) {
                    Text("Go back")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMoveBack = null }) { Text("Cancel") }
            }
        )
    }

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
                    // The Stages behind the runner, off the same reading of the same position
                    // (#235). Only these offer a way back, and only on the plan they are actually
                    // on: a plan they have never activated has no Stage of theirs to return to.
                    val passedStageIds =
                        if (isPlanActive) plan.passedStageIds(selectedStageId) else emptySet()
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
                        // Everything the card says ABOUT the bar — the bar already beaten (#293),
                        // the training counted towards it (#445), the gap still to it (#446) —
                        // stands or falls together, and on one rule stated once. It is the Stage
                        // the runner is in, and only while the plan is unfinished: past the last
                        // graduation "run one now and it counts" is an offer the rule will not
                        // honour, a progress count sits under a congratulation, and a gap names a
                        // target nobody is being asked to reach any more (#294).
                        val saysMoreThanTheBar = isActiveStage && completedLine == null
                        StageCard(
                            stage = stage,
                            isActive = isActiveStage,
                            isLocked = stage.id in lockedStageIds,
                            alreadyBeatenLine = alreadyBeatenLine.takeIf { saysMoreThanTheBar },
                            stageTraining = stageTraining.takeIf { saysMoreThanTheBar },
                            barShortfallLine = barShortfallLine.takeIf { saysMoreThanTheBar },
                            completedLine = completedLine,
                            // Null on every card but the ones behind the runner, which is what
                            // makes the offer unmistakable: the button appears on Stages they have
                            // left and nowhere else.
                            onMoveBack = if (stage.id in passedStageIds) {
                                {
                                    pendingMoveBack = PendingMoveBack(
                                        planId = plan.id,
                                        stageId = stage.id,
                                        stageTitle = stage.title
                                    )
                                }
                            } else {
                                null
                            }
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

/**
 * A Stage the runner has asked to go back to, waiting on their confirmation (#235).
 *
 * Carries the title the card showed rather than looking one up when the dialog draws: the Stage
 * being named in the question is the Stage they were reading, named the same way.
 */
private data class PendingMoveBack(
    val planId: String,
    val stageId: String,
    val stageTitle: String,
)

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
     * The count of this Stage's own training (#445), on the Stage the runner is in and nowhere else.
     * Null on every other card, and while the read is still in flight.
     */
    stageTraining: StageTrainingSummary?,
    /**
     * Where this Stage's timed bar stands against the record book (#446), on the Stage the runner
     * is in and nowhere else. Null on every other card, while the read is still in flight, and on a
     * bar already beaten — where [alreadyBeatenLine] is what the card says instead.
     */
    barShortfallLine: String?,
    /**
     * What this Stage's Requirement has become, on the Stage that finished the plan (#294): the fact
     * that it was met, on a day, in a time. Null on every Stage the runner has not finished a plan
     * on — which is every card but one, and all of them until they do.
     *
     * Non-null is the whole of "this Stage is complete" as far as the card is concerned: the badge,
     * the replaced Requirement line, and the suppressed already-beaten line are one state, not three
     * flags that could disagree.
     */
    completedLine: String?,
    /**
     * What to do when the runner asks to go back to this Stage (#235), or null on a Stage that is
     * not behind them — the Stage they are in, one they have not reached, and every Stage of a plan
     * they are not on.
     */
    onMoveBack: (() -> Unit)?
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
                    // In the beaten line's own place, because it is the same remark on the same
                    // sentence with the other answer in it (#446) — and never beside it: one card
                    // does not both congratulate a time and measure a shortfall against it. Plain
                    // rather than coloured, because a congratulation is news and a gap is a
                    // measurement.
                    if (barShortfallLine != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = barShortfallLine,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    // Under the bar it is counting towards, after any remark on that bar, because it
                    // is about the same sentence and not a second thing the Stage asks for (#445).
                    if (stageTraining != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        StageTrainingBlock(stageTraining)
                    }
                }
            }

            // Below everything the Stage says about itself, because it is not part of what the
            // Stage asks for — it is a door out of the card, and one only a runner already past
            // this Stage is shown (#235). A text button rather than a filled one: going back is a
            // correction the runner occasionally needs, not the thing this screen is for, and a
            // second prominent button beside "Activate Plan" would read as an equal offer.
            if (onMoveBack != null) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onMoveBack,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Go back to this stage")
                }
            }
        }
    }
}

/**
 * The Stage's training record as the runner reads it (#445): the count, the weeks it is spread
 * over, who judges the rest, and which Runs it is made of.
 *
 * Text and a picture of the weeks, and nothing tappable — there is nothing here the app is about to
 * grant, so there is nothing here to press (#293's rule, kept).
 */
@Composable
private fun StageTrainingBlock(summary: StageTrainingSummary) {
    Column {
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = summary.headline,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
        if (summary.weeks.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            StageWeeksRow(summary.weeks)
            if (summary.weeksCaption != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = summary.weeksCaption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (summary.judgementLine != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = summary.judgementLine,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = summary.countedLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One column per week of the record, oldest on the left, empty weeks kept (#445).
 *
 * The height of a column is that week's qualifying Runs against the busiest week's, so the shape
 * answers the question the word *consistent* asks — where the gaps are — rather than the question a
 * total already answers. A week with one Run keeps a visible stub so that "one" and "none" can
 * never draw the same.
 *
 * The counts themselves are read out rather than printed under the columns: twelve numbers across a
 * 320dp card at 1.3x text is a row of numbers nobody can read, and it is the gaps this row exists
 * to show. A screen reader is given every count in order instead.
 */
/**
 * The shortest column a week with a Run may be drawn as, against the busiest week's, so that "one"
 * and "none" can never draw the same.
 */
private const val LEAST_VISIBLE_WEEK = 0.15f

@Composable
private fun StageWeeksRow(weeks: List<StageWeek>) {
    val busiest = weeks.maxOf { it.qualifyingRuns }.coerceAtLeast(1)
    val spoken = weeks.joinToString(", ") { it.qualifyingRuns.toString() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .semantics {
                contentDescription = "Qualifying runs each week, oldest first: $spoken"
            },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        weeks.forEach { week ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.BottomCenter
            ) {
                if (week.qualifyingRuns > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(
                                (week.qualifyingRuns.toFloat() / busiest)
                                    .coerceAtLeast(LEAST_VISIBLE_WEEK)
                            )
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}
