package com.example.runningapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.data.RunnerSession
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    sessions: List<RunnerSession>,
    selectedSessionIds: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onSessionClick: (Long) -> Unit,
    onBack: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val selectedCount = selectedSessionIds.size
    val selectionMode = selectedCount > 0

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("$selectedCount Selected") },
                    navigationIcon = {
                        IconButton(onClick = onClearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("History") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
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
                items(sessions, key = { it.id }) { session ->
                    SessionItem(
                        session = session,
                        isSelected = selectedSessionIds.contains(session.id),
                        onClick = {
                            if (selectionMode) {
                                onToggleSelection(session.id)
                            } else {
                                onSessionClick(session.id)
                            }
                        },
                        onLongClick = { onToggleSelection(session.id) }
                    )
                }
            }
        }
    }

    if (showDeleteDialog && selectedCount > 0) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete selected runs?") },
            text = { Text("Delete $selectedCount selected runs?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteSelected()
                        onClearSelection()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionItem(
    session: RunnerSession,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    val dateStr = sdf.format(Date(session.startTime))
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = dateStr, fontWeight = FontWeight.Bold)
                    if (session.isRunWalkMode) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = Color(0xFFFFA500).copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "RUN/WALK",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFFFA500)
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(text = formatDuration(session.durationSeconds))
                }
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
