package com.example.runningapp.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one sum on the Segment creation screen (#69): how much of the height the controls may take
 * before they have to scroll inside themselves. Pinned here because the failure it exists to stop
 * is invisible — a Column with no room left measures its last child at nothing, so a Save button
 * that does not fit does not overflow where anyone would notice it, it simply is not there.
 */
class SegmentCreateLayoutTest {

    @Test
    fun `a tall screen leaves the map its floor and more`() {
        // A portrait phone: the controls could have 644dp and will never want it, so the map keeps
        // everything they do not take.
        assertEquals(644.dp, segmentControlsMaxHeight(800.dp))
    }

    @Test
    fun `the ceiling never falls below half the screen, however short the screen`() {
        // A landscape phone under a top bar — around 264dp of page. Holding the map's 140dp floor
        // here would leave the controls 108dp, which is less than the slider, the marks, the
        // summary, the name field and the Save button need at a large text size. So the floor is
        // the thing that gives.
        assertEquals(132.dp, segmentControlsMaxHeight(264.dp))
        assertTrue(segmentControlsMaxHeight(264.dp) > 264.dp - MAP_FLOOR_AND_GAP)
    }

    @Test
    fun `the controls are never given nothing, even on a screen shorter than the map floor`() {
        // Multi-window, split down to a strip: the map is what disappears, never the Save button.
        assertEquals(50.dp, segmentControlsMaxHeight(100.dp))
    }

    private companion object {
        /** The floor plus the gap the screen holds back for the map when it can afford to. */
        val MAP_FLOOR_AND_GAP = 156.dp
    }
}
