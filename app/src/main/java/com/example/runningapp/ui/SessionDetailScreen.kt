package com.example.runningapp.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    session: RunnerSession?,
    samples: List<HrSample>,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session Summary") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (session == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                SummaryStats(session)
                Spacer(modifier = Modifier.height(24.dp))
                Text("Heart Rate Chart", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                HrChart(samples = samples, modifier = Modifier.fillMaxWidth().height(200.dp))
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun SummaryStats(session: RunnerSession) {
    val sdf = SimpleDateFormat("EEEE, MMM d, yyyy 'at' HH:mm", Locale.getDefault())
    val dateStr = sdf.format(Date(session.startTime))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = dateStr, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatLarge(label = "Duration", value = formatDurationLarge(session.durationSeconds))
                StatLarge(label = "Avg HR", value = "${session.avgBpm}")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatLarge(label = "Max HR", value = "${session.maxBpm}")
                StatLarge(label = "In Target", value = formatDurationLarge(session.timeInTargetZoneSeconds))
            }
        }
    }
}

@Composable
fun StatLarge(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        Text(text = value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HrChart(samples: List<HrSample>, modifier: Modifier = Modifier) {
    if (samples.isEmpty()) {
        Box(modifier = modifier.background(Color.Black.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) {
            Text("No chart data")
        }
        return
    }

    val maxBpm = samples.maxOf { it.rawBpm }.toFloat().coerceAtLeast(200f)
    val minBpm = samples.minOf { it.rawBpm }.toFloat().coerceAtMost(40f)
    val range = (maxBpm - minBpm).coerceAtLeast(1f)

    Canvas(modifier = modifier.background(Color.Black.copy(alpha = 0.05f)).padding(8.dp)) {
        val width = size.width
        val height = size.height
        
        val path = Path()
        val stepX = width / (samples.size - 1).coerceAtLeast(1)
        
        samples.forEachIndexed { index, sample ->
            val x = index * stepX
            val y = height - ((sample.rawBpm - minBpm) / range * height)
            
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        
        drawPath(
            path = path,
            color = Color.Red,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

private fun formatDurationLarge(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%dh %dm".format(h, m) else "%dm %ds".format(m, s)
}
