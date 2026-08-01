package com.example.runningapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.runningapp.analysis.DistanceChart
import com.example.runningapp.analysis.DistancePoint
import com.example.runningapp.analysis.DistanceTrace
import com.example.runningapp.analysis.ElevationBand
import com.example.runningapp.analysis.formatDistance
import com.example.runningapp.analysis.kilometreTicks
import com.example.runningapp.analysis.HeartRateReading
import com.example.runningapp.analysis.HeartRateTrace
import com.example.runningapp.analysis.RunChart
import com.example.runningapp.data.formatMinutesPerKm
import kotlin.math.roundToInt

/**
 * The Run's analysis chart (#44), drawn from the figures [com.example.runningapp.analysis.RunAnalysis]
 * worked out — this file knows only how to put them on the screen.
 *
 * Two charts, because a Run has two things to be read against. Heart rate over the Run's own clock
 * ([RunAnalysisChart]) is the mode every Run in the history can be shown in, and all a treadmill Run
 * has to offer. An outdoor Run gets [RunCombinedChart] instead (#46): pace and heart rate over the
 * ground it covered, on a silhouette of that ground — which is the version that answers *why*, since
 * the heart-rate spike and the hill that caused it sit in the same place on a distance axis and in
 * quite different places on a clock.
 *
 * Dragging a finger across either puts a line under the finger and reads out what was recorded there.
 */

private val LeftGutter = 32.dp
private val RightGutter = 16.dp
private val TopGutter = 16.dp
private val BottomGutter = 20.dp
private val ChartInset = 8.dp

/** The combined chart carries an axis on each side, and "5:42" is wider than "140". */
private val CombinedLeftGutter = 36.dp
private val CombinedRightGutter = 28.dp

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
                    "${formatMoment(scrubbedReading.elapsedSeconds)} · ${scrubbedReading.bpm} bpm"
                scrubbed != null ->
                    "${formatMoment(scrubbed)} · no reading"
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
                    fun secondUnder(x: Float): Long =
                        (x.toFractionOfRun(leftPaddingPx, size.width - leftPaddingPx - rightPaddingPx) *
                            span).toLong()
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

            // A reading past the ends of the scale — a Strap glitch reporting a beat no runner has —
            // rides the edge of the frame rather than being drawn beyond it. Nothing clips this
            // canvas, so an unclamped one would be painted over the page above and below the chart.
            // The readout under the finger still says the beat that was recorded.
            fun yOf(bpm: Int): Float =
                topPaddingPx + plotHeight -
                    (bpm.coerceIn(chart.bpmFloor, chart.bpmCeiling) - chart.bpmFloor) / bpmRange * plotHeight

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
                drawSeries(
                    trace.readings.map { Offset(xOf(it.elapsedSeconds), yOf(it.bpm)) },
                    HeartRateLine
                )
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

/**
 * The Run over the ground it covered (#46): pace and heart rate drawn on a silhouette of the
 * ground, with the kilometres along the bottom.
 *
 * Three series and one x axis, so the runner can put a finger on a heart-rate spike and see the hill
 * underneath it. The two lines get an axis each — pace on the left, because it is the one the runner
 * came for, and heart rate on the right. The silhouette gets none: it is the shape of the ground,
 * not a figure to read off, and the metres under the finger are in the readout for anyone who wants
 * the number.
 */
@Composable
fun RunCombinedChart(chart: DistanceChart, modifier: Modifier = Modifier) {
    // Where the finger is, in metres along the Run. Null whenever nobody is touching the chart.
    var scrubbedMeters by remember(chart) { mutableStateOf<Double?>(null) }
    val scrubbed = scrubbedMeters
    val scrubbedPoint = scrubbed?.let { chart.readingAt(it) }

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val density = LocalDensity.current
    val leftPaddingPx = with(density) { CombinedLeftGutter.toPx() }
    val rightPaddingPx = with(density) { CombinedRightGutter.toPx() }
    val topPaddingPx = with(density) { TopGutter.toPx() }
    val bottomPaddingPx = with(density) { BottomGutter.toPx() }

    val span = chart.distanceMetersSpan.coerceAtLeast(1.0)
    val paceRange = (chart.paceSlowestMinPerKm - chart.paceFastestMinPerKm).coerceAtLeast(0.1)
    val bpmRange = (chart.bpmCeiling - chart.bpmFloor).coerceAtLeast(1).toFloat()
    val ground = chart.elevationBand
    val heightRange = ground?.let { (it.ceilingMeters - it.floorMeters).coerceAtLeast(1.0) }

    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val scrubberColor = MaterialTheme.colorScheme.onSurfaceVariant
    val silhouetteColor = MaterialTheme.colorScheme.onSurface.copy(alpha = SilhouetteAlpha)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = scrubbedPoint?.let { readoutFor(it) }
                ?: "Drag across the chart to read any point of the run",
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
                    fun metersUnder(x: Float): Double =
                        x.toFractionOfRun(leftPaddingPx, size.width - leftPaddingPx - rightPaddingPx) *
                            span
                    // Horizontal only, so the page underneath keeps its vertical scroll.
                    detectHorizontalDragGestures(
                        onDragStart = { position -> scrubbedMeters = metersUnder(position.x) },
                        onDragEnd = { scrubbedMeters = null },
                        onDragCancel = { scrubbedMeters = null },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            scrubbedMeters = metersUnder(change.position.x)
                        }
                    )
                }
        ) {
            val plotWidth = size.width - leftPaddingPx - rightPaddingPx
            val plotHeight = size.height - topPaddingPx - bottomPaddingPx
            val plotBottom = topPaddingPx + plotHeight

            fun xOf(meters: Double): Float =
                leftPaddingPx + (meters / span).toFloat() * plotWidth

            // Every series is clamped to its own scale before it is drawn, because nothing clips a
            // Compose canvas: an unclamped point rides over the page above and below the chart
            // rather than over the frame (#44).
            fun yOfPace(paceMinPerKm: Double): Float =
                topPaddingPx +
                    ((paceMinPerKm.coerceIn(chart.paceFastestMinPerKm, chart.paceSlowestMinPerKm) -
                        chart.paceFastestMinPerKm) / paceRange).toFloat() * plotHeight

            fun yOfBpm(bpm: Int): Float =
                plotBottom - (bpm.coerceIn(chart.bpmFloor, chart.bpmCeiling) - chart.bpmFloor) / bpmRange * plotHeight

            // The ground is drawn in the bottom band of the plot and the lines above it, so the
            // silhouette reads as what the runner was on rather than as a third line to compare.
            fun yOfHeight(meters: Double): Float {
                if (ground == null || heightRange == null) return plotBottom
                val fraction = ((meters - ground.floorMeters) / heightRange).coerceIn(0.0, 1.0).toFloat()
                return plotBottom - fraction * plotHeight * SilhouetteShareOfPlot
            }

            // -- The ground, first, so both lines are drawn over it -----------------------------
            if (ground != null) {
                chart.traces.forEach { trace ->
                    val heights = trace.points.filter { it.metersAboveLowestPoint != null }
                    if (heights.size < 2) return@forEach
                    val silhouette = Path()
                    silhouette.moveTo(xOf(heights.first().distanceMeters), plotBottom)
                    heights.forEach { silhouette.lineTo(xOf(it.distanceMeters), yOfHeight(it.metersAboveLowestPoint!!)) }
                    silhouette.lineTo(xOf(heights.last().distanceMeters), plotBottom)
                    silhouette.close()
                    drawPath(path = silhouette, color = silhouetteColor)
                }
            }

            // -- The frame ----------------------------------------------------------------------
            val yTicks = 4
            for (tick in 0 until yTicks) {
                val fraction = tick / (yTicks - 1).toFloat()
                val y = plotBottom - fraction * plotHeight

                drawLine(
                    color = gridColor,
                    start = Offset(leftPaddingPx, y),
                    end = Offset(size.width - rightPaddingPx, y),
                    strokeWidth = 1.dp.toPx()
                )

                // Pace on the left, upside down against the others: faster is a smaller number, and
                // a runner reads the top of a pace chart as their best stretch.
                val pace = chart.paceSlowestMinPerKm - fraction * paceRange
                val paceLabel = textMeasurer.measure(formatMinutesPerKm(pace), style = labelStyle)
                drawText(
                    textLayoutResult = paceLabel,
                    topLeft = Offset(leftPaddingPx - paceLabel.size.width - 4.dp.toPx(), y - paceLabel.size.height / 2)
                )

                val bpm = chart.bpmFloor + fraction * bpmRange
                val bpmLabel = textMeasurer.measure(bpm.toInt().toString(), style = labelStyle)
                drawText(
                    textLayoutResult = bpmLabel,
                    topLeft = Offset(size.width - rightPaddingPx + 4.dp.toPx(), y - bpmLabel.size.height / 2)
                )
            }

            kilometreTicks(span).forEach { meters ->
                val label = textMeasurer.measure(formatDistance(meters), style = labelStyle)
                val x = xOf(meters)
                val textX = (x - label.size.width / 2)
                    .coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f))
                drawText(
                    textLayoutResult = label,
                    topLeft = Offset(textX, plotBottom + 4.dp.toPx())
                )
            }

            // -- The two lines ------------------------------------------------------------------
            // One path per run of points that has the value, inside one stretch of the recording:
            // a break in the recording and a Strap dropout are both stretches nothing was measured
            // in, and drawing across either would invent a line through them.
            chart.traces.forEach { trace ->
                trace.points.stretchesOf { it.paceMinPerKm }.forEach { measured ->
                    drawSeries(measured.map { Offset(xOf(it.first.distanceMeters), yOfPace(it.second)) }, PaceLine)
                }
                trace.points.stretchesOf { it.bpm }.forEach { measured ->
                    drawSeries(measured.map { Offset(xOf(it.first.distanceMeters), yOfBpm(it.second)) }, HeartRateLine)
                }
            }

            scrubbedPoint?.let { point ->
                val x = xOf(point.distanceMeters)
                drawLine(
                    color = scrubberColor,
                    start = Offset(x, topPaddingPx),
                    end = Offset(x, plotBottom),
                    strokeWidth = 1.dp.toPx()
                )
                point.paceMinPerKm?.let { drawCircle(PaceLine, 4.dp.toPx(), Offset(x, yOfPace(it))) }
                point.bpm?.let { drawCircle(HeartRateLine, 4.dp.toPx(), Offset(x, yOfBpm(it))) }
            }
        }

        // Only the series the Run actually recorded: an outdoor Run with no Strap draws no red
        // line, and a key naming one sends the runner looking for it.
        ChartKey(
            hasPace = chart.traces.any { trace -> trace.points.any { it.paceMinPerKm != null } },
            hasHeartRate = chart.traces.any { trace -> trace.points.any { it.bpm != null } },
            hasElevation = ground != null,
        )
    }
}

/** Which line is which, said in words — two coloured lines on one chart are otherwise a guess. */
@Composable
private fun ChartKey(hasPace: Boolean, hasHeartRate: Boolean, hasElevation: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (hasPace) KeyEntry(PaceLine, "Pace")
        if (hasHeartRate) KeyEntry(HeartRateLine, "Heart rate")
        // The same wash the silhouette is filled with, so the key names the thing on the chart.
        if (hasElevation) KeyEntry(MaterialTheme.colorScheme.onSurface.copy(alpha = SilhouetteAlpha), "Elevation")
    }
}

@Composable
private fun KeyEntry(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(width = 10.dp, height = 8.dp).background(color))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * What the finger is over, said the way the rest of the page says it: how far in, then each of the
 * three, and only the ones the Run actually recorded there.
 */
internal fun readoutFor(point: DistancePoint): String = buildList {
    add(formatDistance(point.distanceMeters))
    point.paceMinPerKm?.let { add("${formatMinutesPerKm(it)} /km") }
    point.bpm?.let { add("$it bpm") }
    // "+18 m" rather than "18 m": the number is a rise above the run's own lowest point, not an
    // altitude, and the sign is what says so.
    point.metersAboveLowestPoint?.let { add("+${it.roundToInt()} m above the run's low point") }
}.joinToString(" · ")

/**
 * The points cut into the stretches that have the value asked for, each drawable as one line.
 *
 * Every point keeps its own place along the Run, so a Strap that dropped out for a minute leaves a
 * gap where it dropped out rather than the line closing up over it.
 */
internal fun <T : Any> List<DistancePoint>.stretchesOf(
    valueOf: (DistancePoint) -> T?,
): List<List<Pair<DistancePoint, T>>> {
    val measured = mutableListOf<MutableList<Pair<DistancePoint, T>>>()
    var open = false
    forEach { point ->
        val value = valueOf(point)
        if (value == null) {
            open = false
            return@forEach
        }
        if (!open) measured.add(mutableListOf())
        measured.last() += point to value
        open = true
    }
    return measured
}

/** A run of points as one stroke, or as a dot where there is only one of them to draw. */
private fun DrawScope.drawSeries(points: List<Offset>, color: Color) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color = color, radius = 2.dp.toPx(), center = points.single())
        return
    }
    val path = Path()
    points.forEachIndexed { index, offset ->
        if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
    }
    drawPath(path = path, color = color, style = Stroke(width = 2.dp.toPx()))
}

/**
 * How far into the Run a touch at [this] horizontal pixel lands, as a fraction of it — clamped to
 * the Run itself, so a finger dragged off either side of the plot reads its nearest end.
 *
 * Both charts scrub the same way and differ only in what the axis counts, so the pixels become a
 * fraction here and the caller turns that into seconds or metres.
 */
private fun Float.toFractionOfRun(plotLeft: Float, plotWidth: Float): Float {
    if (plotWidth <= 0f) return 0f
    return ((this - plotLeft) / plotWidth).coerceIn(0f, 1f)
}

/** Blue, the colour of effort over ground — distinct from the heart's red at a glance. */
private val PaceLine = Color(0xFF2E6FF2)

/** Red, the way a heart rate has always been drawn here — the colour the old chart used (#44). */
private val HeartRateLine = Color.Red

/** How much of the plot's height the ground is allowed, leaving the rest to the two lines. */
private const val SilhouetteShareOfPlot = 0.45f

/** How faint the ground is: present enough to read as a shape, faint enough to sit under two lines. */
private const val SilhouetteAlpha = 0.10f

/** An axis tick: a round point on the Run's clock, kept short enough to sit under the chart. */
private fun formatElapsed(seconds: Long): String =
    if (seconds < 3600) {
        "%02d:%02d".format(seconds / 60, seconds % 60)
    } else {
        "%dh %02dm".format(seconds / 3600, (seconds % 3600) / 60)
    }

/**
 * The one moment under the finger, said to the second.
 *
 * Not the axis's wording: an hour into a Run that reads "1h 00m" names sixty different seconds, each
 * with its own beat, and the readout's whole job is to say which one the runner is on.
 */
private fun formatMoment(seconds: Long): String =
    if (seconds < 3600) {
        "%02d:%02d".format(seconds / 60, seconds % 60)
    } else {
        "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
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

@Preview(showBackground = true)
@Composable
private fun PreviewRunCombinedChart() {
    // A kilometre and a half over a hill, with a lost signal a third of the way in.
    fun stretch(fromMeters: Int, toMeters: Int, step: Int = 25) = (fromMeters..toMeters step step).map { meters ->
        val up = kotlin.math.sin(meters / 900.0 * Math.PI)
        DistancePoint(
            distanceMeters = meters.toDouble(),
            paceMinPerKm = 5.4 + up * 1.4,
            metersAboveLowestPoint = 25.0 + up * 25.0,
            bpm = (128 + up * 26).roundToInt(),
        )
    }
    val chart = DistanceChart(
        traces = listOf(DistanceTrace(stretch(0, 500)), DistanceTrace(stretch(500, 1_500))),
        distanceMetersSpan = 1_500.0,
        bpmFloor = 120,
        bpmCeiling = 160,
        paceFastestMinPerKm = 5.0,
        paceSlowestMinPerKm = 7.0,
        elevationBand = ElevationBand(floorMeters = 35.0, ceilingMeters = 70.0),
    )
    MaterialTheme {
        RunCombinedChart(chart = chart, modifier = Modifier.padding(16.dp))
    }
}
