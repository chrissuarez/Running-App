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
import androidx.compose.foundation.lazy.items
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingPlanScreen(
    activePlanId: String?,
    activeStageId: String?,
    onActivatePlan: (planId: String, stageId: String) -> Unit,
    onBack: () -> Unit
) {
    val plan = TrainingPlanProvider.getPlanById("5k_sub_25") ?: return
    val isPlanActive = activePlanId == plan.id
    val firstStageId = plan.stages.firstOrNull()?.id
    val fallbackActiveStageId = plan.stages.firstOrNull { !it.isLocked }?.id

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
            item {
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
                }
            }

            items(plan.stages) { stage ->
                StageCard(
                    stage = stage,
                    isActive = stage.id == (
                        if (isPlanActive) {
                            activeStageId ?: firstStageId ?: fallbackActiveStageId
                        } else {
                            firstStageId ?: fallbackActiveStageId
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun StageCard(
    stage: PlanStage,
    isActive: Boolean
) {
    val cardColor = when {
        isActive -> MaterialTheme.colorScheme.primaryContainer
        stage.isLocked -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (stage.isLocked) 0.72f else 1f),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stage.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                when {
                    stage.isLocked -> {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked stage",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
            }
        }
    }
}
