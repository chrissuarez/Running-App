package com.example.runningapp.ui.workout

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.runningapp.HrZone
import com.example.runningapp.ZoneBand

/**
 * Two palettes, two jobs — and green belongs to exactly one of them.
 *
 * [zoneBandColor] answers *"am I doing it right?"* on the live screen; [zoneChartColor] answers
 * *"what did the run cost?"* on the history chart. They are deliberately different scales, so
 * green never appears on the chart: it is the one colour that means **on target**, and nothing
 * else (#106).
 */
@Composable
fun zoneBandColor(zoneBand: ZoneBand): Color = when (zoneBand) {
    ZoneBand.BELOW -> Color(0xFF8FD0FF)
    ZoneBand.IN -> Color(0xFF9CF7AD)
    ZoneBand.ABOVE -> MaterialTheme.colorScheme.error
    ZoneBand.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Cool to hot, so intensity reads down the chart at a glance. See [zoneBandColor] on green. */
fun zoneChartColor(zone: HrZone): Color = when (zone) {
    HrZone.ENDURANCE -> Color(0xFF8CA3B8)
    HrZone.MODERATE -> Color(0xFF5B9BF8)
    HrZone.TEMPO -> Color(0xFFF2C037)
    HrZone.THRESHOLD -> Color(0xFFFF8A3D)
    HrZone.ANAEROBIC -> Color(0xFFFF6B6B)
}
