package com.example.runningapp.ui.workout

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The live map card's gestures (#357).
 *
 * The card's map must not handle a touch of its own: every gesture it claims is a tap the runner
 * meant as "open the full-screen map" and never got. The full-screen map is not covered here — it
 * keeps Mapbox's own defaults, which this change does not touch.
 */
class LiveMapGesturesTest {

    @Test
    fun `the card's map claims no gesture at all`() {
        val settings = noGesturesSettings()
        assertFalse(settings.scrollEnabled)
        assertFalse(settings.pinchToZoomEnabled)
        assertFalse(settings.doubleTapToZoomInEnabled)
        assertFalse(settings.doubleTouchToZoomOutEnabled)
        assertFalse(settings.quickZoomEnabled)
        assertFalse(settings.simultaneousRotateAndPinchToZoomEnabled)
        assertFalse(settings.rotateEnabled)
        assertFalse(settings.pitchEnabled)
    }
}
