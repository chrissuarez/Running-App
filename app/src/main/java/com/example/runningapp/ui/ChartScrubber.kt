package com.example.runningapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Where the runner's finger is on the Run's chart, in metres along the Run — or null when nobody is
 * touching it (#48).
 *
 * Its own thing rather than the chart's private state, because the route map above the chart wants
 * the same number: the dot on the route and the line under the finger are one reading of one Run,
 * shown in two places. Neither owns it, so it sits between them.
 *
 * In metres, because that is the axis the chart is drawn against and the scale the map measures its
 * own route along ([com.example.runningapp.analysis.TrackMap.fixAt]) — the one number both of them
 * already understand.
 *
 * A holder rather than a value handed down with a callback, so that a drag repaints the chart and
 * the dot and nothing else. The page around them is a scrolling column of cards worked out from a
 * whole Run, and recomposing all of it sixty times a second is the jank this exists to avoid.
 */
@Stable
class ChartScrubber {
    var distanceMeters: Double? by mutableStateOf(null)
}

/**
 * A [ChartScrubber] for as long as the page lives.
 *
 * Unkeyed deliberately. Keying it on the Run would mean comparing a Run's whole analysis — every
 * fix, every reading — on each recomposition, which is a walk of the Run per frame of a drag; and it
 * would buy nothing, because the scrubber holds a value only while a finger is on the chart, and a
 * finger cannot still be down on a page the runner has left.
 */
@Composable
fun rememberChartScrubber(): ChartScrubber = remember { ChartScrubber() }
