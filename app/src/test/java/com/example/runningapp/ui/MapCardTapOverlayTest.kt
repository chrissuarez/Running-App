package com.example.runningapp.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corner the map cards' tap layer leaves to Mapbox (#409).
 *
 * Every figure here was measured on a Pixel 8a (420 dpi, so 2.625 px to the dp) off the Run-detail
 * page, and converted to dp from the map's own top-left corner. The card's map box was
 * [42,1023]-[1038,1548] in screen pixels, which is 379 dp across and 200 dp tall.
 *
 * What is checked is the SIZE of the corner, which is the half of [MapCardTapOverlay] that can be
 * trimmed by eye. Where the hole actually is, is a phone test's answer — the layout is built from
 * the same two constants, so a size that is too small is the failure this can see, and it is a
 * silent one: a trimmed corner still draws the "i" and still opens the full-screen map when the
 * card is tapped, and the only thing that changes is that the attribution can no longer be
 * reached — which is the one thing the terms this app uses Mapbox's maps under do not allow.
 */
class MapCardTapOverlayTest {

    private val cardWidthDp = 379f
    private val cardHeightDp = 200f

    private fun inCorner(xDp: Float, yDp: Float) = isInMapboxCorner(xDp, yDp, cardHeightDp)

    @Test
    fun `the attribution icon the phone drew is inside the corner`() {
        // Drawn centre (313,1510) in screen pixels.
        assertTrue(inCorner(xDp = 103.2f, yDp = 185.5f))
    }

    @Test
    fun `every edge of that icon is inside the corner, not just its middle`() {
        // A 48 dp touch target around that centre, sat on the map's bottom edge.
        assertTrue(inCorner(xDp = 79.2f, yDp = 161.5f))
        assertTrue(inCorner(xDp = 127.2f, yDp = 161.5f))
        assertTrue(inCorner(xDp = 79.2f, yDp = 200f))
        assertTrue(inCorner(xDp = 127.2f, yDp = 200f))
    }

    @Test
    fun `the logo wordmark beside it is inside the corner too`() {
        // Drawn from 6 dp to 88 dp across, on the same bottom edge.
        assertTrue(inCorner(xDp = 6f, yDp = 190f))
        assertTrue(inCorner(xDp = 88f, yDp = 190f))
    }

    @Test
    fun `the middle of the card is not, so the card still opens the map`() {
        assertFalse(inCorner(xDp = cardWidthDp / 2f, yDp = cardHeightDp / 2f))
    }

    @Test
    fun `the other end of the bottom edge is not, so only one corner stops opening the map`() {
        assertFalse(inCorner(xDp = cardWidthDp - 8f, yDp = cardHeightDp - 8f))
    }

    @Test
    fun `the top-left corner is not - the hole is at the bottom, not up the whole side`() {
        assertFalse(inCorner(xDp = 8f, yDp = 8f))
    }

    @Test
    fun `the shorter in-run card holds its own icon too`() {
        // The in-run card is 180 dp tall, not 200, and it is the shorter of the two that the corner
        // has to fit inside. Measured on the same phone with the hole cut: its map box was
        // [79,1510]-[1001,1983] and the "i" was drawn at (349,1943), which is 103 dp across and
        // 165 dp down. The 48 dp touch target around it sits on the card's bottom edge.
        val inRunHeightDp = 180f
        assertTrue(isInMapboxCorner(xDp = 103f, yDp = 165f, mapHeightDp = inRunHeightDp))
        assertTrue(isInMapboxCorner(xDp = 79f, yDp = 141f, mapHeightDp = inRunHeightDp))
        assertTrue(isInMapboxCorner(xDp = 127f, yDp = 141f, mapHeightDp = inRunHeightDp))
        assertTrue(isInMapboxCorner(xDp = 127f, yDp = inRunHeightDp, mapHeightDp = inRunHeightDp))
    }

    @Test
    fun `the shorter card still opens the map from its middle`() {
        assertFalse(isInMapboxCorner(xDp = cardWidthDp / 2f, yDp = 90f, mapHeightDp = 180f))
    }
}
