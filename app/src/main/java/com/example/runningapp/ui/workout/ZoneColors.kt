package com.example.runningapp.ui.workout

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun zoneBandColor(zoneBand: ZoneBand): Color = when (zoneBand) {
    ZoneBand.BELOW -> Color(0xFF8FD0FF)
    ZoneBand.IN -> Color(0xFF9CF7AD)
    ZoneBand.ABOVE -> MaterialTheme.colorScheme.error
    ZoneBand.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}
