package com.example.runningapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.line.lineSpec
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec
import com.patrykandpatrick.vico.compose.m3.style.m3ChartStyle
import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.entryOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One point of a trend: how far into the chart it sits, and what it was worth. */
data class TrendChartPoint(val dayOffset: Int, val value: Float)

/**
 * A history drawn against the calendar it happened on.
 *
 * One chart, two pages: the times run at a Segment (#72) and the paces run on a matched route (#73).
 * Written once because they are the same picture and have to stay the same picture — a runner
 * should not have to learn two charts to read their own repetition.
 *
 * Drawn against the day rather than against the attempt number, so the gaps between attempts are the
 * gaps that really happened. Even spacing would make the chart's own claim — whether the runner is
 * getting quicker across months and years — a lie about their own history.
 *
 * Styled as the Progress screen's charts are: the Material palette through [m3ChartStyle], three
 * dates along the bottom, no vertical guidelines, no shading under the line, and no scrolling, so
 * the whole history is on screen at once. The dates carry their year, which the Progress screen's do
 * not: that screen looks back a year at most, and these look back as far as the runner has been
 * going, so "24 Jul" alone would not say which July.
 */
@Composable
fun TrendLineChart(
    points: List<TrendChartPoint>,
    /** The day [TrendChartPoint.dayOffset] counts from, which is what turns an x back into a date. */
    firstDay: LocalDate,
    /** The values up the side, read back as the thing they are — a time, a pace. */
    valueLabel: (Float) -> String,
    /** What the chart says out loud, because a picture says nothing on its own. */
    spoken: String,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return

    // Handed to the producer as it is built rather than pushed into an empty one afterwards: a Vico
    // chart measured against an empty model throws, and an effect runs a frame too late (#63).
    val producer = remember(points) {
        ChartEntryModelProducer(points.map { entryOf(it.dayOffset.toFloat(), it.value) })
    }

    val dateLabels = AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
        firstDay.plusDays(value.toLong()).format(TrendDateFormat)
    }
    val valueLabels = AxisValueFormatter<AxisPosition.Vertical.Start> { value, _ -> valueLabel(value) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TrendChartHeight)
            .semantics { contentDescription = spoken }
    ) {
        ProvideChartStyle(m3ChartStyle()) {
            Chart(
                // The line alone, as on the Progress screen: Vico shades under a line by default,
                // and a trend that starts at zero would be four fifths shading and one fifth chart.
                // `lineBackgroundShader = null` is that screen's own answer to it.
                chart = lineChart(
                    lines = listOf(
                        lineSpec(
                            lineColor = MaterialTheme.colorScheme.primary,
                            lineBackgroundShader = null,
                        )
                    )
                ),
                chartModelProducer = producer,
                startAxis = rememberStartAxis(valueFormatter = valueLabels),
                bottomAxis = rememberBottomAxis(
                    valueFormatter = dateLabels,
                    itemPlacer = threeLabelPlacer(trendAxisTicks(points.map { it.dayOffset })),
                    guideline = null,
                ),
                chartScrollSpec = rememberChartScrollSpec(isScrollEnabled = false),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** How tall a trend chart is — the height the Progress screen's own line chart stands at. */
private val TrendChartHeight = 240.dp

private val TrendDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yy", Locale.UK)
