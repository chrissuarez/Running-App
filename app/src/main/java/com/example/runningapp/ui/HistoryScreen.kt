package com.example.runningapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.runningapp.data.RunnerSession
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    sessions: List<RunnerSession>,
    onSessionClick: (Long) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No sessions recorded yet.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions) { session ->
                    SessionItem(session = session, onClick = { onSessionClick(session.id) })
                }
            }
        }
    }
}

@Composable
fun SessionItem(session: RunnerSession, onClick: () -> Unit) {
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    val dateStr = sdf.format(Date(session.startTime))
    
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = dateStr, fontWeight = FontWeight.Bold)
                Text(text = formatDuration(session.durationSeconds))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatSmall(label = "Avg HR", value = "${session.avgBpm}")
                if (session.runMode == "outdoor") {
                    StatSmall(label = "Dist", value = "%.2f km".format(session.distanceKm))
                    val p = session.avgPaceMinPerKm
                    val pStr = if (p > 0) "%d:%02d".format(p.toInt(), ((p - p.toInt()) * 60).roundToInt()) else "--:--"
                    StatSmall(label = "Pace", value = pStr)
                }
                StatSmall(label = "Target", value = formatDuration(session.timeInTargetZoneSeconds))
            }
        }
    }
}

@Composable
fun StatSmall(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
