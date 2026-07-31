package com.example.runningapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.runningapp.analysis.HeartRateReading
import com.example.runningapp.analysis.HeartRateTrace
import com.example.runningapp.analysis.RunChart

/**
 * The Run's analysis chart (#44), drawn from the figures [com.example.runningapp.analysis.RunAnalysis]
 * worked out — this file knows only how to put them on the screen.
 *
 * Heart rate over the Run's elapsed clock is the mode every Run in the history can be shown in, so
 * it is the one this ticket draws; #46 adds the pace and elevation series and the distance axis that
 * outdoor Runs can also offer. Dragging a finger across it puts a line under the finger and reads
 * out the beat and the moment it was measured in.
 */

private val LeftGutter = 32.dp
private val RightGutter = 16.dp
private val TopGutter = 16.dp
private val BottomGutter = 20.dp
private val ChartInset = 8.dp

/** The drawing surface's own height, so the chart is the same size whatever it has to show. */
private val ChartHeight = 200.dp

@Composable
fun RunAnalysisChart(chart: RunChart?, modifier: Modifier = Modifier) {
    if (chart == null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(ChartHeight)
                .background(Color.Black.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            Text("No heart rate was recorded for this run")
        }
        return
    }

    // Where the finger is, in the Run's own seconds. Null whenever nobody is touching the chart, so
    // the scrubber leaves nothing behind once the runner lifts their finger.
    var scrubbedSecond by remember(chart) { mutableStateOf<Long?>(null) }
    val scrubbed = scrubbedSecond
    val scrubbedReading = scrubbed?.let { chart.readingAt(it) }

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val density = LocalDensity.current
    val leftPaddingPx = with(density) { LeftGutter.toPx() }
    val rightPaddingPx = with(density) { RightGutter.toPx() }
    val topPaddingPx = with(density) { TopGutter.toPx() }
    val bottomPaddingPx = with(density) { BottomGutter.toPx() }

    val bpmRange = (chart.bpmCeiling - chart.bpmFloor).coerceAtLeast(1).toFloat()
    val span = chart.elapsedSecondsSpan.coerceAtLeast(1)

    val scrubberColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = when {
                scrubbedReading != null ->
                    "${formatElapsed(scrubbedReading.elapsedSeconds)} · ${scrubbedReading.bpm} bpm"
                scrubbed != null ->
                    "${formatElapsed(scrubbed)} · no reading"
                else -> "Drag across the chart to read any moment of the run"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChartHeight)
                .background(Color.Black.copy(alpha = 0.05f))
                .padding(ChartInset)
                .pointerInput(chart) {
                    // Touches land in the coordinates the chart is drawn in: the padding above
                    // wraps this gesture, so both start at the top left of the drawing surface.
                    fun secondUnder(x: Float): Long = x.toElapsedSecond(
                        plotLeft = leftPaddingPx,
                        plotWidth = size.width - leftPaddingPx - rightPaddingPx,
                        span = span
                    )
                    // Horizontal only, so the page underneath keeps its vertical scroll: the runner
                    // drags across the chart to inspect it and up the page to leave it.
                    detectHorizontalDragGestures(
                        onDragStart = { position -> scrubbedSecond = secondUnder(position.x) },
                        onDragEnd = { scrubbedSecond = null },
                        onDragCancel = { scrubbedSecond = null },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            scrubbedSecond = secondUnder(change.position.x)
                        }
                    )
                }
        ) {
            val plotWidth = size.width - leftPaddingPx - rightPaddingPx
            val plotHeight = size.height - topPaddingPx - bottomPaddingPx

            fun xOf(elapsedSeconds: Long): Float =
                leftPaddingPx + elapsedSeconds.toFloat() / span * plotWidth

            fun yOf(bpm: Int): Float =
                topPaddingPx + plotHeight - (bpm - chart.bpmFloor) / bpmRange * plotHeight

            val yTicks = 5
            for (tick in 0 until yTicks) {
                val fraction = tick / (yTicks - 1).toFloat()
                val bpm = chart.bpmFloor + fraction * bpmRange
                val y = topPaddingPx + plotHeight - fraction * plotHeight

                drawLine(
                    color = Color.LightGray.copy(alpha = 0.5f),
                    start = Offset(leftPaddingPx, y),
                    end = Offset(size.width - rightPaddingPx, y),
                    strokeWidth = 1.dp.toPx()
                )

                val label = textMeasurer.measure(bpm.toInt().toString(), style = labelStyle)
                drawText(
                    textLayoutResult = label,
                    topLeft = Offset(leftPaddingPx - label.size.width - 4.dp.toPx(), y - label.size.height / 2)
                )
            }

            val xTicks = if (span < 120) 3 else 4
            for (tick in 0 until xTicks) {
                val fraction = tick / (xTicks - 1).toFloat()
                val second = (fraction * span).toLong()
                val x = leftPaddingPx + fraction * plotWidth

                val label = textMeasurer.measure(formatElapsed(second), style = labelStyle)
                val textX = when (tick) {
                    0 -> x
                    xTicks - 1 -> x - label.size.width
                    else -> x - label.size.width / 2
                }
                drawText(
                    textLayoutResult = label,
                    topLeft = Offset(textX, topPaddingPx + plotHeight + 4.dp.toPx())
                )
            }

            // One path per unbroken stretch: the gaps are where the Run recorded no heart rate, and
            // drawing across them would invent a line through a minute nothing was measured in.
            chart.heartRate.forEach { trace ->
                if (trace.readings.size == 1) {
                    val only = trace.readings.single()
                    drawCircle(
                        color = HeartRateLine,
                        radius = 2.dp.toPx(),
                        center = Offset(xOf(only.elapsedSeconds), yOf(only.bpm))
                    )
                    return@forEach
                }
                val path = Path()
                trace.readings.forEachIndexed { index, reading ->
                    val x = xOf(reading.elapsedSeconds)
                    val y = yOf(reading.bpm)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = HeartRateLine, style = Stroke(width = 2.dp.toPx()))
            }

            scrubbedSecond?.let { second ->
                val x = xOf(second)
                drawLine(
                    color = scrubberColor,
                    start = Offset(x, topPaddingPx),
                    end = Offset(x, topPaddingPx + plotHeight),
                    strokeWidth = 1.dp.toPx()
                )
                scrubbedReading?.let { reading ->
                    drawCircle(
                        color = HeartRateLine,
                        radius = 4.dp.toPx(),
                        center = Offset(xOf(reading.elapsedSeconds), yOf(reading.bpm))
                    )
                }
            }
        }
    }
}

/** Red, the way a heart rate has always been drawn here — the colour the old chart used (#44). */
private val HeartRateLine = Color.Red

/** Where on the Run's clock a touch at [this] horizontal pixel lands, clamped to the Run itself. */
private fun Float.toElapsedSecond(plotLeft: Float, plotWidth: Float, span: Long): Long {
    if (plotWidth <= 0f) return 0
    val fraction = ((this - plotLeft) / plotWidth).coerceIn(0f, 1f)
    return (fraction * span).toLong()
}

private fun formatElapsed(seconds: Long): String =
    if (seconds < 3600) {
        "%02d:%02d".format(seconds / 60, seconds % 60)
    } else {
        "%dh %02dm".format(seconds / 3600, (seconds % 3600) / 60)
    }

@Preview(showBackground = true)
@Composable
private fun PreviewRunAnalysisChart() {
    val chart = RunChart(
        heartRate = listOf(
            HeartRateTrace(
                listOf(
                    HeartRateReading(0, 70),
                    HeartRateReading(30, 85),
                    HeartRateReading(60, 110),
                    HeartRateReading(90, 140)
                )
            ),
            HeartRateTrace(listOf(HeartRateReading(200, 135), HeartRateReading(240, 150)))
        ),
        elapsedSecondsSpan = 240,
        bpmFloor = 60,
        bpmCeiling = 160
    )
    MaterialTheme {
        RunAnalysisChart(chart = chart, modifier = Modifier.padding(16.dp))
    }
}
