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
 * A test and not a comment because the failure this guards is silent: a corner trimmed to look
 * tidier still draws the "i" and still opens the full-screen map when it is tapped, and the only
 * thing that changes is that the attribution can no longer be reached — which is the one thing the
 * terms this app uses Mapbox's maps under do not allow.
 */
class MapCardTapOverlayTest {

    private val cardWidthDp = 379f
    private val cardHeightDp = 200f

    private fun inCorner(xDp: Float, yDp: Float) =
        isInMapboxCorner(xDp, yDp, cardWidthDp, cardHeightDp)

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
    fun `the bottom-right badge's corner is not, so distance-to-go stays over the layer`() {
        assertFalse(inCorner(xDp = cardWidthDp - 8f, yDp = cardHeightDp - 8f))
    }

    @Test
    fun `the top-left corner is not - the hole is at the bottom, not up the whole side`() {
        assertFalse(inCorner(xDp = 8f, yDp = 8f))
    }
}
