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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
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

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = Color.Gray)

    val rawMax = samples.maxOf { it.rawBpm }.toFloat()
    val rawMin = samples.minOf { it.rawBpm }.toFloat()
    
    // Rounded range for cleaner labels
    val chartMin = (rawMin - 5f).coerceAtLeast(40f).let { (it / 10).toInt() * 10f }
    val chartMax = (rawMax + 5f).coerceAtMost(220f).let { ((it + 9) / 10).toInt() * 10f }
    val bpmRange = (chartMax - chartMin).coerceAtLeast(1f)

    val durationSeconds = samples.last().elapsedSeconds

    Canvas(modifier = modifier.background(Color.Black.copy(alpha = 0.05f)).padding(8.dp)) {
        val leftPadding = 32.dp.toPx() // Decreased slightly to fit text better
        val bottomPadding = 20.dp.toPx()
        val topPadding = 16.dp.toPx() // Improved top spacing
        val rightPadding = 16.dp.toPx() // Improved right spacing

        val chartWidth = size.width - leftPadding - rightPadding
        val chartHeight = size.height - bottomPadding - topPadding

        // 1. Draw Y-axis labels and horizontal grid lines (5 ticks)
        val yTicks = 5
        for (i in 0 until yTicks) {
            val fraction = i / (yTicks - 1).toFloat()
            val bpm = chartMin + (fraction * bpmRange)
            val y = topPadding + chartHeight - (fraction * chartHeight)
            
            // Grid line
            drawLine(
                color = Color.LightGray.copy(alpha = 0.5f),
                start = Offset(leftPadding, y),
                end = Offset(size.width - rightPadding, y),
                strokeWidth = 1.dp.toPx()
            )
            
            // Label
            val labelLayout = textMeasurer.measure(bpm.toInt().toString(), style = labelStyle)
            drawText(
                textLayoutResult = labelLayout,
                topLeft = Offset(leftPadding - labelLayout.size.width - 4.dp.toPx(), y - labelLayout.size.height / 2)
            )
        }

        // 2. Draw X-axis labels (3-4 ticks based on duration)
        val xTicks = if (durationSeconds < 120) 3 else 4
        for (i in 0 until xTicks) {
            val fraction = i / (xTicks - 1).toFloat()
            val seconds = (fraction * durationSeconds).toLong()
            val x = leftPadding + (fraction * chartWidth)
            
            val label = if (durationSeconds < 3600) {
                "%02d:%02d".format(seconds / 60, seconds % 60)
            } else {
                "%dh %dm".format(seconds / 3600, (seconds % 3600) / 60)
            }
            
            val labelLayout = textMeasurer.measure(label, style = labelStyle)
            
            // Adjust X position to keep text within bounds
            val textX = when (i) {
                0 -> x // Left aligned
                xTicks - 1 -> x - labelLayout.size.width // Right aligned
                else -> x - labelLayout.size.width / 2 // Center aligned
            }
            
            drawText(
                textLayoutResult = labelLayout,
                topLeft = Offset(textX, topPadding + chartHeight + 4.dp.toPx())
            )
        }

        // 3. Draw HR Path
        val path = Path()
        samples.forEachIndexed { index, sample ->
            val x = leftPadding + (sample.elapsedSeconds.toFloat() / durationSeconds.coerceAtLeast(1) * chartWidth)
            val y = topPadding + chartHeight - ((sample.rawBpm - chartMin) / bpmRange * chartHeight)
            
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
@Preview(showBackground = true)
@Composable
fun PreviewHrChart() {
    val samples = listOf(
        HrSample(1, 1, 0, 70, 70, "CONNECTED"),
        HrSample(2, 1, 30, 85, 80, "CONNECTED"),
        HrSample(3, 1, 60, 110, 100, "CONNECTED"),
        HrSample(4, 1, 90, 140, 130, "CONNECTED"),
        HrSample(5, 1, 120, 135, 135, "CONNECTED"),
        HrSample(6, 1, 150, 155, 150, "CONNECTED")
    )
    MaterialTheme {
        HrChart(
            samples = samples,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(16.dp)
        )
    }
}
