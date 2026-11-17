package com.example.runningapp.export

import com.example.runningapp.data.RunnerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GarminPostTest {

    private val end = 1_763_400_000_000L
    private val hour = 60 * 60 * 1000L

    private fun run(
        id: Long = 12L,
        endTime: Long = end,
        runMode: String = "outdoor",
        distanceKm: Double = 5.0,
    ) = RunnerSession(id = id, startTime = end - hour, endTime = endTime, runMode = runMode, distanceKm = distanceKm)

    // -- Whether to ask (#544) ----------------------------------------------------------------------

    @Test
    fun `a Run just finished and never asked about is asked about`() {
        assertTrue(GarminPostOffer.shouldAsk(run(), askedThroughRunId = 11L, nowMillis = end + 5_000))
    }

    @Test
    fun `a Run already asked about is never asked again`() {
        assertFalse(GarminPostOffer.shouldAsk(run(id = 12L), askedThroughRunId = 12L, nowMillis = end + 5_000))
    }

    @Test
    fun `an older Run than one already asked about is not asked about`() {
        assertFalse(GarminPostOffer.shouldAsk(run(id = 10L), askedThroughRunId = 12L, nowMillis = end + 5_000))
    }

    @Test
    fun `a Run still being recorded is not asked about`() {
        assertFalse(GarminPostOffer.shouldAsk(run(endTime = 0L), askedThroughRunId = 0L, nowMillis = end))
    }

    @Test
    fun `an old Run opened from history is not asked about`() {
        val now = end + GarminPostOffer.FRESH_FOR_MILLIS + 1
        assertFalse(GarminPostOffer.shouldAsk(run(), askedThroughRunId = 0L, nowMillis = now))
    }

    @Test
    fun `a Run opened late on the same day is still asked about`() {
        val now = end + GarminPostOffer.FRESH_FOR_MILLIS
        assertTrue(GarminPostOffer.shouldAsk(run(), askedThroughRunId = 0L, nowMillis = now))
    }

    @Test
    fun `a treadmill Run waits for its distance before it is asked about`() {
        val noDistance = run(runMode = "treadmill", distanceKm = 0.0)
        assertFalse(GarminPostOffer.shouldAsk(noDistance, askedThroughRunId = 0L, nowMillis = end + 5_000))
        val stated = noDistance.copy(distanceKm = 6.2)
        assertTrue(GarminPostOffer.shouldAsk(stated, askedThroughRunId = 0L, nowMillis = end + 5_000))
    }

    @Test
    fun `an outdoor Run with no distance is still asked about`() {
        // Only a treadmill is waited on: outdoors, no distance means no GPS, and nothing is coming.
        assertTrue(
            GarminPostOffer.shouldAsk(run(distanceKm = 0.0), askedThroughRunId = 0L, nowMillis = end + 5_000)
        )
    }

    // -- The page's file box ------------------------------------------------------------------------

    @Test
    fun `the file box is answered with the Run's file`() {
        assertEquals(listOf("content://fit"), GarminWebPage.chooserAnswer("content://fit"))
    }

    @Test
    fun `with no file the box is cancelled rather than left hanging`() {
        assertNull(GarminWebPage.chooserAnswer<String>(null))
    }

    // -- Where the page may go ----------------------------------------------------------------------

    @Test
    fun `Garmin and its sign-in stay in the app`() {
        listOf(
            "https://connect.garmin.com/app/import-data",
            "https://sso.garmin.com/portal/sso/en-GB/sign-in",
            "https://garmin.com/",
            "https://accounts.google.com/o/oauth2/auth",
            "https://appleid.apple.com/auth/authorize",
            "about:blank",
            "data:text/html,<p>hi</p>",
            "blob:https://connect.garmin.com/1234",
        ).forEach { assertTrue(it, GarminWebPage.staysInApp(it)) }
    }

    @Test
    fun `anything else opens outside the app`() {
        listOf(
            "https://www.example.com/",
            "https://evilgarmin.com/",
            "https://garmin.com.evil.example/",
            "http://connect.garmin.com/",
            "mailto:support@garmin.com",
            "intent://scan/#Intent;scheme=zxing;end",
            "not a url",
        ).forEach { assertFalse(it, GarminWebPage.staysInApp(it)) }
    }

    // -- How the page sees the app ------------------------------------------------------------------

    @Test
    fun `the WebView mark is taken out of the user agent`() {
        val webView = "Mozilla/5.0 (Linux; Android 14; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/131.0.6778.39 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.6778.39 Mobile Safari/537.36",
            GarminWebPage.mobileUserAgent(webView)
        )
    }

    @Test
    fun `a user agent with no WebView mark is left alone`() {
        val chrome = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.6778.39 Mobile Safari/537.36"
        assertEquals(chrome, GarminWebPage.mobileUserAgent(chrome))
    }
}
