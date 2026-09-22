package com.example.runningapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.example.runningapp.ui.theme.RunningUiTokens

@Composable
fun SettingsSummaryCard(
    settings: UserSettings
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(RunningUiTokens.CardPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val target = settings.targetHrZone
                Text(
                    "Zone ${target.number} · ${target.zoneName}: ${targetRangeLabel(target, settings.hrProfile)} BPM",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                val modeLabel = if (settings.runMode == "outdoor") "Outdoor Run" else "Treadmill Run"
                Text("Mode: $modeLabel", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (settings.coachingEnabled) "Coaching ON" else "Coaching OFF",
                style = MaterialTheme.typography.bodySmall,
                color = if (settings.coachingEnabled) Color(0xFF9CF7AD) else MaterialTheme.colorScheme.error
            )
        }
    }
}
